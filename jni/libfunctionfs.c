/******************************************************************************
 * libfunctionfs.c
 *
 * Copyright (c) 2026, werismoln <vlkanblek@gmail.com>
 *
 * GPLv3+
 *
 * MultiBooter FunctionFS USB Mass-Storage backend.
 *
 * 2026-08 compatibility fixes:
 *   - Correct BOT OUT-data draining so an unsupported command cannot shift
 *     the next CBW and corrupt the transport stream.
 *   - Persistent SCSI sense data instead of returning NO SENSE after every
 *     failed command.
 *   - Windows-relevant SCSI commands modelled after Linux f_mass_storage:
 *       READ(6), READ(10), READ(12), READ(16)
 *       READ CAPACITY(10), READ CAPACITY(16)
 *       READ FORMAT CAPACITIES
 *       READ HEADER, READ TOC
 *       VERIFY(10)
 *       MODE SENSE(6/10)
 *   - Minimal INQUIRY VPD support for Windows storage enumeration.
 *   - Safer bounds checking and chunked ISO reads.
 ******************************************************************************/

#include <jni.h>

#include <android/log.h>
#include <endian.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usb/ch9.h>
#include <linux/usb/functionfs.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define TAG "MultiBooterFFS"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define FFS_DIR  "/dev/usb-ffs/multiboot"
#define EP0_PATH FFS_DIR "/ep0"
#define EP1_PATH FFS_DIR "/ep1"
#define EP2_PATH FFS_DIR "/ep2"

#ifndef USB_CLASS_MASS_STORAGE
#define USB_CLASS_MASS_STORAGE 0x08
#endif

#define SCSI_TRANSPARENT_SUBCLASS    0x06
#define BULK_ONLY_TRANSPORT_PROTOCOL 0x50

#define USB_DIR_IN_VALUE  0x80
#define USB_DIR_OUT_VALUE 0x00

#define USB_TYPE_CLASS_VALUE      0x20
#define USB_RECIP_INTERFACE_VALUE 0x01

#define BOT_REQUEST_RESET       0xFF
#define BOT_REQUEST_GET_MAX_LUN 0xFE

#define CBW_SIGNATURE 0x43425355u
#define CSW_SIGNATURE 0x53425355u

#define BOT_STATUS_GOOD        0x00
#define BOT_STATUS_FAILED      0x01
#define BOT_STATUS_PHASE_ERROR 0x02

/* CD-ROM logical sector size. */
#define LOGICAL_BLOCK_SIZE 2048u
#define IO_CHUNK_SIZE      (128u * 1024u)

/* SCSI / MMC opcodes used by Linux f_mass_storage and Windows. */
#define SCSI_TEST_UNIT_READY         0x00
#define SCSI_REQUEST_SENSE           0x03
#define SCSI_READ_6                  0x08
#define SCSI_INQUIRY                 0x12
#define SCSI_MODE_SELECT_6           0x15
#define SCSI_MODE_SENSE_6            0x1A
#define SCSI_START_STOP_UNIT         0x1B
#define SCSI_PREVENT_ALLOW           0x1E
#define SCSI_READ_FORMAT_CAPACITIES  0x23
#define SCSI_READ_CAPACITY_10        0x25
#define SCSI_READ_10                 0x28
#define SCSI_VERIFY_10               0x2F
#define SCSI_SYNCHRONIZE_CACHE_10    0x35
#define SCSI_READ_TOC                0x43
#define SCSI_READ_HEADER             0x44
#define SCSI_GET_CONFIGURATION       0x46
#define SCSI_MODE_SELECT_10          0x55
#define SCSI_MODE_SENSE_10           0x5A
#define SCSI_READ_16                 0x88
#define SCSI_SERVICE_ACTION_IN_16    0x9E
#define SCSI_READ_12                 0xA8

#define SCSI_SAI_READ_CAPACITY_16    0x10

/* Sense keys. */
#define SENSE_NO_SENSE        0x00
#define SENSE_MEDIUM_ERROR    0x03
#define SENSE_ILLEGAL_REQUEST 0x05
#define SENSE_UNIT_ATTENTION  0x06
#define SENSE_DATA_PROTECT    0x07

/* ASC/ASCQ values. */
#define ASC_NO_ADDITIONAL_SENSE            0x00
#define ASC_INVALID_COMMAND_OPERATION_CODE 0x20
#define ASC_LOGICAL_BLOCK_OUT_OF_RANGE     0x21
#define ASC_INVALID_FIELD_IN_CDB            0x24
#define ASC_WRITE_PROTECTED                 0x27
#define ASC_MEDIUM_CHANGED                  0x28
#define ASC_UNRECOVERED_READ_ERROR          0x11

#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define CONST_LE16(x) ((uint16_t)(x))
#define CONST_LE32(x) ((uint32_t)(x))
#else
#define CONST_LE16(x) __builtin_bswap16((uint16_t)(x))
#define CONST_LE32(x) __builtin_bswap32((uint32_t)(x))
#endif

struct CBW {
    uint32_t Signature;
    uint32_t Tag;
    uint32_t DataTransferLength;
    uint8_t  Flags;
    uint8_t  LUN;
    uint8_t  CBLength;
    uint8_t  CDB[16];
} __attribute__((packed));

struct CSW {
    uint32_t Signature;
    uint32_t Tag;
    uint32_t DataResidue;
    uint8_t  Status;
} __attribute__((packed));

_Static_assert(sizeof(struct CBW) == 31, "CBW must be 31 bytes");
_Static_assert(sizeof(struct CSW) == 13, "CSW must be 13 bytes");

/* ------------------------------------------------------------------------- */
/* FunctionFS descriptors                                                     */
/* ------------------------------------------------------------------------- */

static const struct {
    struct usb_functionfs_descs_head_v2 header;
    __le32 fs_count;
    __le32 hs_count;

    struct {
        struct usb_interface_descriptor intf;
        struct usb_endpoint_descriptor_no_audio ep_in;
        struct usb_endpoint_descriptor_no_audio ep_out;
    } __attribute__((packed)) fs_descs;

    struct {
        struct usb_interface_descriptor intf;
        struct usb_endpoint_descriptor_no_audio ep_in;
        struct usb_endpoint_descriptor_no_audio ep_out;
    } __attribute__((packed)) hs_descs;

} __attribute__((packed)) descriptors = {
    .header = {
        .magic  = CONST_LE32(FUNCTIONFS_DESCRIPTORS_MAGIC_V2),
        .length = CONST_LE32(sizeof(descriptors)),
        .flags  = CONST_LE32(
            FUNCTIONFS_HAS_FS_DESC |
            FUNCTIONFS_HAS_HS_DESC
        ),
    },

    .fs_count = CONST_LE32(3),
    .hs_count = CONST_LE32(3),

    .fs_descs = {
        .intf = {
            .bLength            = sizeof(struct usb_interface_descriptor),
            .bDescriptorType    = USB_DT_INTERFACE,
            .bInterfaceNumber   = 0,
            .bAlternateSetting  = 0,
            .bNumEndpoints      = 2,
            .bInterfaceClass    = USB_CLASS_MASS_STORAGE,
            .bInterfaceSubClass = SCSI_TRANSPARENT_SUBCLASS,
            .bInterfaceProtocol = BULK_ONLY_TRANSPORT_PROTOCOL,
            .iInterface         = 1,
        },
        .ep_in = {
            .bLength          = sizeof(struct usb_endpoint_descriptor_no_audio),
            .bDescriptorType  = USB_DT_ENDPOINT,
            .bEndpointAddress = 1 | USB_DIR_IN_VALUE,
            .bmAttributes     = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize   = CONST_LE16(64),
            .bInterval        = 0,
        },
        .ep_out = {
            .bLength          = sizeof(struct usb_endpoint_descriptor_no_audio),
            .bDescriptorType  = USB_DT_ENDPOINT,
            .bEndpointAddress = 2 | USB_DIR_OUT_VALUE,
            .bmAttributes     = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize   = CONST_LE16(64),
            .bInterval        = 0,
        },
    },

    .hs_descs = {
        .intf = {
            .bLength            = sizeof(struct usb_interface_descriptor),
            .bDescriptorType    = USB_DT_INTERFACE,
            .bInterfaceNumber   = 0,
            .bAlternateSetting  = 0,
            .bNumEndpoints      = 2,
            .bInterfaceClass    = USB_CLASS_MASS_STORAGE,
            .bInterfaceSubClass = SCSI_TRANSPARENT_SUBCLASS,
            .bInterfaceProtocol = BULK_ONLY_TRANSPORT_PROTOCOL,
            .iInterface         = 1,
        },
        .ep_in = {
            .bLength          = sizeof(struct usb_endpoint_descriptor_no_audio),
            .bDescriptorType  = USB_DT_ENDPOINT,
            .bEndpointAddress = 1 | USB_DIR_IN_VALUE,
            .bmAttributes     = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize   = CONST_LE16(512),
            .bInterval        = 0,
        },
        .ep_out = {
            .bLength          = sizeof(struct usb_endpoint_descriptor_no_audio),
            .bDescriptorType  = USB_DT_ENDPOINT,
            .bEndpointAddress = 2 | USB_DIR_OUT_VALUE,
            .bmAttributes     = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize   = CONST_LE16(512),
            .bInterval        = 0,
        },
    },
};

#define STR_INTERFACE_ "MultiBooter Virtual CD-ROM"

static const struct {
    struct usb_functionfs_strings_head header;
    struct {
        __le16 code;
        const char str1[sizeof(STR_INTERFACE_)];
    } __attribute__((packed)) lang0;
} __attribute__((packed)) strings = {
    .header = {
        .magic      = CONST_LE32(FUNCTIONFS_STRINGS_MAGIC),
        .length     = CONST_LE32(sizeof(strings)),
        .str_count  = CONST_LE32(1),
        .lang_count = CONST_LE32(1),
    },
    .lang0 = {
        .code = CONST_LE16(0x0409),
        .str1 = STR_INTERFACE_,
    },
};

/* ------------------------------------------------------------------------- */
/* Global state                                                               */
/* ------------------------------------------------------------------------- */

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static pthread_t g_event_thread;
static pthread_t g_scsi_thread;

static int g_event_thread_created = 0;
static int g_scsi_thread_created = 0;

static volatile int g_stop_requested = 0;
static volatile int g_running = 0;
static volatile int g_scsi_running = 0;

static int g_ep0 = -1;
static int g_ep_in = -1;
static int g_ep_out = -1;

static char *g_iso_path = NULL;
static char g_last_error[512] = "";

struct sense_state {
    uint8_t key;
    uint8_t asc;
    uint8_t ascq;
    uint32_t information;
    int information_valid;
};

static struct sense_state g_sense = {
    SENSE_NO_SENSE,
    ASC_NO_ADDITIONAL_SENSE,
    0,
    0,
    0
};

/* ------------------------------------------------------------------------- */
/* Utility                                                                    */
/* ------------------------------------------------------------------------- */

static void set_last_error(const char *message)
{
    pthread_mutex_lock(&g_lock);

    if (message == NULL) {
        g_last_error[0] = '\0';
    } else {
        snprintf(g_last_error, sizeof(g_last_error), "%s", message);
    }

    pthread_mutex_unlock(&g_lock);
}

static void set_last_errno(const char *prefix)
{
    char buffer[512];
    int saved_errno = errno;

    snprintf(
        buffer,
        sizeof(buffer),
        "%s: %s",
        prefix,
        strerror(saved_errno)
    );

    set_last_error(buffer);
}

static void sense_clear(void)
{
    g_sense.key = SENSE_NO_SENSE;
    g_sense.asc = ASC_NO_ADDITIONAL_SENSE;
    g_sense.ascq = 0;
    g_sense.information = 0;
    g_sense.information_valid = 0;
}

static void sense_set(uint8_t key, uint8_t asc, uint8_t ascq)
{
    g_sense.key = key;
    g_sense.asc = asc;
    g_sense.ascq = ascq;
    g_sense.information = 0;
    g_sense.information_valid = 0;
}

static void sense_set_lba(uint8_t key, uint8_t asc, uint32_t lba)
{
    g_sense.key = key;
    g_sense.asc = asc;
    g_sense.ascq = 0;
    g_sense.information = lba;
    g_sense.information_valid = 1;
}

static ssize_t read_full(int fd, void *buffer, size_t length)
{
    uint8_t *cursor = (uint8_t *)buffer;
    size_t done = 0;

    while (done < length) {
        ssize_t result = read(fd, cursor + done, length - done);

        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }

        if (result == 0) {
            return (ssize_t)done;
        }

        done += (size_t)result;
    }

    return (ssize_t)done;
}

static ssize_t write_full(int fd, const void *buffer, size_t length)
{
    const uint8_t *cursor = (const uint8_t *)buffer;
    size_t done = 0;

    while (done < length) {
        ssize_t result = write(fd, cursor + done, length - done);

        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }

        if (result == 0) {
            return (ssize_t)done;
        }

        done += (size_t)result;
    }

    return (ssize_t)done;
}

static uint16_t read_be16(const uint8_t *data)
{
    return (uint16_t)(
        ((uint16_t)data[0] << 8) |
        ((uint16_t)data[1])
    );
}

static uint32_t read_be24(const uint8_t *data)
{
    return
        ((uint32_t)data[0] << 16) |
        ((uint32_t)data[1] << 8) |
        ((uint32_t)data[2]);
}

static uint32_t read_be32(const uint8_t *data)
{
    return
        ((uint32_t)data[0] << 24) |
        ((uint32_t)data[1] << 16) |
        ((uint32_t)data[2] << 8) |
        ((uint32_t)data[3]);
}

static uint64_t read_be64(const uint8_t *data)
{
    return
        ((uint64_t)data[0] << 56) |
        ((uint64_t)data[1] << 48) |
        ((uint64_t)data[2] << 40) |
        ((uint64_t)data[3] << 32) |
        ((uint64_t)data[4] << 24) |
        ((uint64_t)data[5] << 16) |
        ((uint64_t)data[6] << 8) |
        ((uint64_t)data[7]);
}

static void write_be16(uint8_t *data, uint16_t value)
{
    data[0] = (uint8_t)((value >> 8) & 0xFF);
    data[1] = (uint8_t)(value & 0xFF);
}

static void write_be32(uint8_t *data, uint32_t value)
{
    data[0] = (uint8_t)((value >> 24) & 0xFF);
    data[1] = (uint8_t)((value >> 16) & 0xFF);
    data[2] = (uint8_t)((value >> 8) & 0xFF);
    data[3] = (uint8_t)(value & 0xFF);
}

static void write_be64(uint8_t *data, uint64_t value)
{
    data[0] = (uint8_t)((value >> 56) & 0xFF);
    data[1] = (uint8_t)((value >> 48) & 0xFF);
    data[2] = (uint8_t)((value >> 40) & 0xFF);
    data[3] = (uint8_t)((value >> 32) & 0xFF);
    data[4] = (uint8_t)((value >> 24) & 0xFF);
    data[5] = (uint8_t)((value >> 16) & 0xFF);
    data[6] = (uint8_t)((value >> 8) & 0xFF);
    data[7] = (uint8_t)(value & 0xFF);
}

static uint64_t file_size_bytes(FILE *file)
{
    off_t original;
    off_t end;

    if (file == NULL) {
        return 0;
    }

    original = ftello(file);
    if (original < 0) {
        original = 0;
    }

    if (fseeko(file, 0, SEEK_END) != 0) {
        return 0;
    }

    end = ftello(file);
    (void)fseeko(file, original, SEEK_SET);

    return end < 0 ? 0 : (uint64_t)end;
}

static uint64_t total_blocks_for_size(uint64_t size)
{
    if (size == 0) {
        return 0;
    }

    return
        (size + LOGICAL_BLOCK_SIZE - 1) /
        LOGICAL_BLOCK_SIZE;
}

static int run_root_command(const char *command)
{
    char shell[2048];
    int result;

    if (command == NULL) {
        return -1;
    }

    snprintf(shell, sizeof(shell), "su -c '%s'", command);
    result = system(shell);

    return result == 0 ? 0 : -1;
}

static int is_functionfs_mounted(void)
{
    FILE *mounts;
    char line[1024];

    mounts = fopen("/proc/mounts", "r");
    if (mounts == NULL) {
        return 0;
    }

    while (fgets(line, sizeof(line), mounts) != NULL) {
        if (
            strstr(line, " " FFS_DIR " ") != NULL &&
            strstr(line, " functionfs ") != NULL
        ) {
            fclose(mounts);
            return 1;
        }
    }

    fclose(mounts);
    return 0;
}

static int prepare_functionfs_mount(void)
{
    uid_t uid = getuid();
    gid_t gid = getgid();
    char command[1024];

    if (access(EP0_PATH, R_OK | W_OK) == 0) {
        return 0;
    }

    if (!is_functionfs_mounted()) {
        snprintf(
            command,
            sizeof(command),
            "mkdir -p " FFS_DIR
            " && mount -t functionfs"
            " -o uid=%u,gid=%u,rmode=0770,fmode=0660,mode=0770"
            " multiboot " FFS_DIR,
            (unsigned int)uid,
            (unsigned int)gid
        );

        if (run_root_command(command) != 0) {
            set_last_error("Could not mount FunctionFS through root.");
            return -1;
        }
    }

    if (access(EP0_PATH, R_OK | W_OK) != 0) {
        snprintf(
            command,
            sizeof(command),
            "chown %u:%u " EP0_PATH
            " && chmod 0660 " EP0_PATH,
            (unsigned int)uid,
            (unsigned int)gid
        );

        if (run_root_command(command) != 0) {
            set_last_error(
                "FunctionFS ep0 exists but is not accessible to the app."
            );
            return -1;
        }
    }

    if (access(EP0_PATH, R_OK | W_OK) != 0) {
        set_last_error(
            "FunctionFS ep0 is still inaccessible after root preparation."
        );
        return -1;
    }

    return 0;
}

static int open_data_endpoints_with_retry(int *ep_in, int *ep_out)
{
    int attempt;

    if (ep_in == NULL || ep_out == NULL) {
        return -1;
    }

    *ep_in = -1;
    *ep_out = -1;

    for (attempt = 0; attempt < 150 && !g_stop_requested; ++attempt) {
        int in_fd = open(EP1_PATH, O_RDWR | O_CLOEXEC);
        int out_fd = open(EP2_PATH, O_RDWR | O_CLOEXEC);

        if (in_fd >= 0 && out_fd >= 0) {
            *ep_in = in_fd;
            *ep_out = out_fd;
            return 0;
        }

        if (in_fd >= 0) {
            close(in_fd);
        }
        if (out_fd >= 0) {
            close(out_fd);
        }

        if (
            attempt == 10 &&
            (access(EP1_PATH, F_OK) == 0 || access(EP2_PATH, F_OK) == 0)
        ) {
            char command[1024];

            snprintf(
                command,
                sizeof(command),
                "chown %u:%u " EP1_PATH " " EP2_PATH
                " 2>/dev/null || true; "
                "chmod 0660 " EP1_PATH " " EP2_PATH
                " 2>/dev/null || true",
                (unsigned int)getuid(),
                (unsigned int)getgid()
            );

            (void)run_root_command(command);
        }

        usleep(20 * 1000);
    }

    return -1;
}

/* ------------------------------------------------------------------------- */
/* BOT helpers                                                                */
/* ------------------------------------------------------------------------- */

static uint32_t cbw_transfer_length(const struct CBW *cbw)
{
    return le32toh(cbw->DataTransferLength);
}

static int cbw_is_in(const struct CBW *cbw)
{
    return (cbw->Flags & USB_DIR_IN_VALUE) != 0;
}

static int send_data_in(
    int ep_in,
    const struct CBW *cbw,
    struct CSW *csw,
    const void *data,
    size_t data_length
)
{
    uint32_t expected;
    size_t send_length;

    if (cbw == NULL || csw == NULL) {
        return -1;
    }

    expected = cbw_transfer_length(cbw);

    if (expected > 0 && !cbw_is_in(cbw)) {
        csw->Status = BOT_STATUS_PHASE_ERROR;
        csw->DataResidue = htole32(expected);
        return 0;
    }

    send_length = data_length;
    if (send_length > expected) {
        send_length = expected;
    }

    if (
        send_length > 0 &&
        write_full(ep_in, data, send_length) != (ssize_t)send_length
    ) {
        return -1;
    }

    csw->DataResidue = htole32(expected - (uint32_t)send_length);
    return 0;
}

static int terminate_in_data_phase(int ep_in, const struct CBW *cbw)
{
    if (cbw_transfer_length(cbw) == 0 || !cbw_is_in(cbw)) {
        return 0;
    }

    /* Explicit short/ZLP so the host can proceed to the CSW. */
    for (;;) {
        ssize_t result = write(ep_in, NULL, 0);
        if (result >= 0) {
            return 0;
        }
        if (errno != EINTR) {
            return -1;
        }
    }
}

static int drain_data_out(int ep_out, uint32_t length)
{
    uint8_t buffer[16 * 1024];
    uint32_t left = length;

    while (left > 0 && !g_stop_requested) {
        size_t chunk = left;
        ssize_t got;

        if (chunk > sizeof(buffer)) {
            chunk = sizeof(buffer);
        }

        got = read(ep_out, buffer, chunk);

        if (got < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }

        if (got == 0) {
            return -1;
        }

        left -= (uint32_t)got;
    }

    return left == 0 ? 0 : -1;
}

static int fail_scsi_command(
    int ep_in,
    int ep_out,
    const struct CBW *cbw,
    struct CSW *csw,
    uint8_t sense_key,
    uint8_t asc,
    uint8_t ascq
)
{
    uint32_t expected = cbw_transfer_length(cbw);

    sense_set(sense_key, asc, ascq);
    csw->Status = BOT_STATUS_FAILED;
    csw->DataResidue = htole32(expected);

    /*
     * Critical BOT rule:
     * If the host declared an OUT data phase, consume it before CSW.
     * Otherwise the payload becomes the next "CBW" and the stream is lost.
     */
    if (expected > 0) {
        if (cbw_is_in(cbw)) {
            if (terminate_in_data_phase(ep_in, cbw) != 0) {
                return -1;
            }
        } else {
            if (drain_data_out(ep_out, expected) != 0) {
                return -1;
            }
        }
    }

    return 0;
}

static void store_cdrom_address(uint8_t *dest, int msf, uint32_t lba)
{
    if (!msf) {
        write_be32(dest, lba);
        return;
    }

    /* MMC MSF addresses are biased by 150 frames (2 seconds). */
    lba += 150;

    dest[0] = 0;
    dest[1] = (uint8_t)(lba / (60u * 75u));
    dest[2] = (uint8_t)((lba / 75u) % 60u);
    dest[3] = (uint8_t)(lba % 75u);
}

/* ------------------------------------------------------------------------- */
/* SCSI data helpers                                                          */
/* ------------------------------------------------------------------------- */

static int send_file_blocks(
    int ep_in,
    FILE *iso_file,
    uint64_t iso_size,
    uint64_t start_lba,
    uint64_t blocks,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint64_t total_blocks = total_blocks_for_size(iso_size);
    uint64_t requested_bytes_64;
    uint64_t offset;
    uint32_t expected;
    uint64_t bytes_to_send;
    uint64_t sent = 0;
    uint8_t *buffer;

    if (blocks == 0) {
        csw->DataResidue = cbw->DataTransferLength;
        return 0;
    }

    if (
        total_blocks == 0 ||
        start_lba >= total_blocks ||
        blocks > total_blocks - start_lba
    ) {
        sense_set_lba(
            SENSE_ILLEGAL_REQUEST,
            ASC_LOGICAL_BLOCK_OUT_OF_RANGE,
            (uint32_t)(start_lba > UINT32_MAX ? UINT32_MAX : start_lba)
        );
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    if (blocks > UINT64_MAX / LOGICAL_BLOCK_SIZE) {
        return -1;
    }

    if (start_lba > UINT64_MAX / LOGICAL_BLOCK_SIZE) {
        return -1;
    }

    requested_bytes_64 = blocks * LOGICAL_BLOCK_SIZE;
    offset = start_lba * LOGICAL_BLOCK_SIZE;
    expected = cbw_transfer_length(cbw);

    if (!cbw_is_in(cbw) && expected > 0) {
        csw->Status = BOT_STATUS_PHASE_ERROR;
        return 0;
    }

    bytes_to_send = requested_bytes_64;
    if (bytes_to_send > expected) {
        bytes_to_send = expected;
        csw->Status = BOT_STATUS_PHASE_ERROR;
    } else if (bytes_to_send < expected) {
        /* Host and CDB disagree. Transfer the command amount, then phase error. */
        csw->Status = BOT_STATUS_PHASE_ERROR;
    }

    buffer = (uint8_t *)malloc(IO_CHUNK_SIZE);
    if (buffer == NULL) {
        return -1;
    }

    if (fseeko(iso_file, (off_t)offset, SEEK_SET) != 0) {
        free(buffer);
        return -1;
    }

    while (sent < bytes_to_send && !g_stop_requested) {
        size_t chunk = (size_t)(bytes_to_send - sent);
        uint64_t absolute = offset + sent;
        size_t real_bytes = 0;

        if (chunk > IO_CHUNK_SIZE) {
            chunk = IO_CHUNK_SIZE;
        }

        memset(buffer, 0, chunk);

        if (absolute < iso_size) {
            uint64_t available = iso_size - absolute;
            real_bytes = chunk;

            if ((uint64_t)real_bytes > available) {
                real_bytes = (size_t)available;
            }

            if (real_bytes > 0) {
                size_t got = fread(buffer, 1, real_bytes, iso_file);

                if (got != real_bytes) {
                    if (ferror(iso_file)) {
                        sense_set_lba(
                            SENSE_MEDIUM_ERROR,
                            ASC_UNRECOVERED_READ_ERROR,
                            (uint32_t)(
                                (start_lba + sent / LOGICAL_BLOCK_SIZE) > UINT32_MAX
                                    ? UINT32_MAX
                                    : (start_lba + sent / LOGICAL_BLOCK_SIZE)
                            )
                        );
                        csw->Status = BOT_STATUS_FAILED;
                        free(buffer);
                        return -1;
                    }

                    /* EOF: remaining bytes stay zero for a partial final block. */
                    clearerr(iso_file);
                }
            }
        }

        if (write_full(ep_in, buffer, chunk) != (ssize_t)chunk) {
            free(buffer);
            return -1;
        }

        sent += chunk;
    }

    free(buffer);

    if (g_stop_requested) {
        return -1;
    }

    csw->DataResidue = htole32(expected - (uint32_t)sent);
    return 0;
}

static int handle_inquiry(
    int ep_in,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t data[96];
    size_t length = 0;
    uint8_t evpd = cbw->CDB[1] & 0x01;
    uint8_t page = cbw->CDB[2];
    size_t allocation = cbw->CDB[4];

    memset(data, 0, sizeof(data));

    if (!evpd) {
        if (page != 0) {
            sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
            csw->Status = BOT_STATUS_FAILED;
            return terminate_in_data_phase(ep_in, cbw);
        }

        data[0] = 0x05; /* CD/DVD device */
        data[1] = 0x80; /* removable */
        data[2] = 0x02; /* ANSI SCSI-2 */
        data[3] = 0x02; /* SCSI-2 response data format */
        data[4] = 31;

        memcpy(&data[8],  "MULTIBOT", 8);
        memcpy(&data[16], "Virtual CD-ROM  ", 16);
        memcpy(&data[32], "1.10", 4);
        length = 36;

    } else if (page == 0x00) {
        data[0] = 0x05;
        data[1] = 0x00;
        data[2] = 0;
        data[3] = 3;
        data[4] = 0x00;
        data[5] = 0x80;
        data[6] = 0x83;
        length = 7;

    } else if (page == 0x80) {
        static const char serial[] = "MULTIBOOTER0001";
        size_t serial_len = sizeof(serial) - 1;

        data[0] = 0x05;
        data[1] = 0x80;
        data[2] = 0;
        data[3] = (uint8_t)serial_len;
        memcpy(&data[4], serial, serial_len);
        length = 4 + serial_len;

    } else if (page == 0x83) {
        static const char ident[] = "MULTIBOT VirtualCD MULTIBOOTER0001";
        size_t ident_len = sizeof(ident) - 1;

        data[0] = 0x05;
        data[1] = 0x83;
        write_be16(&data[2], (uint16_t)(4 + ident_len));

        /* ASCII, T10 vendor-id style identification descriptor. */
        data[4] = 0x02;
        data[5] = 0x01;
        data[6] = 0x00;
        data[7] = (uint8_t)ident_len;
        memcpy(&data[8], ident, ident_len);
        length = 8 + ident_len;

    } else {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    if (allocation < length) {
        length = allocation;
    }

    return send_data_in(ep_in, cbw, csw, data, length);
}

static int handle_request_sense(
    int ep_in,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t sense[18];
    size_t allocation = cbw->CDB[4];
    size_t length = sizeof(sense);

    memset(sense, 0, sizeof(sense));

    sense[0] = (uint8_t)(
        0x70 |
        (g_sense.information_valid ? 0x80 : 0x00)
    );
    sense[2] = g_sense.key;

    if (g_sense.information_valid) {
        write_be32(&sense[3], g_sense.information);
    }

    sense[7] = 10;
    sense[12] = g_sense.asc;
    sense[13] = g_sense.ascq;

    if (allocation < length) {
        length = allocation;
    }

    /* Sense is cleared only after it has been reported. */
    sense_clear();

    return send_data_in(ep_in, cbw, csw, sense, length);
}

static int handle_read_capacity_10(
    int ep_in,
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t data[8];
    uint64_t blocks = total_blocks_for_size(iso_size);
    uint32_t last_lba;
    uint32_t requested_lba = read_be32(&cbw->CDB[2]);
    int pmi = cbw->CDB[8] & 0x01;

    if ((cbw->CDB[8] & ~0x01) != 0 || (!pmi && requested_lba != 0)) {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    if (blocks == 0) {
        last_lba = 0;
    } else if (blocks - 1 > UINT32_MAX) {
        last_lba = UINT32_MAX;
    } else {
        last_lba = (uint32_t)(blocks - 1);
    }

    write_be32(&data[0], last_lba);
    write_be32(&data[4], LOGICAL_BLOCK_SIZE);

    return send_data_in(ep_in, cbw, csw, data, sizeof(data));
}

static int handle_read_capacity_16(
    int ep_in,
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t data[32];
    uint64_t blocks = total_blocks_for_size(iso_size);
    uint64_t requested_lba = read_be64(&cbw->CDB[2]);
    int pmi = cbw->CDB[14] & 0x01;
    uint32_t allocation = read_be32(&cbw->CDB[10]);
    size_t length = sizeof(data);

    if ((cbw->CDB[14] & ~0x01) != 0 || (!pmi && requested_lba != 0)) {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    memset(data, 0, sizeof(data));
    write_be64(&data[0], blocks == 0 ? 0 : blocks - 1);
    write_be32(&data[8], LOGICAL_BLOCK_SIZE);

    if (allocation < length) {
        length = allocation;
    }

    return send_data_in(ep_in, cbw, csw, data, length);
}

static int handle_read_format_capacities(
    int ep_in,
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t data[12];
    uint64_t blocks64 = total_blocks_for_size(iso_size);
    uint32_t blocks =
        blocks64 > UINT32_MAX ? UINT32_MAX : (uint32_t)blocks64;
    size_t allocation = read_be16(&cbw->CDB[7]);
    size_t length = sizeof(data);

    memset(data, 0, sizeof(data));
    data[3] = 8;
    write_be32(&data[4], blocks);

    /* Capacity descriptor code 0x02 + 24-bit block length 2048. */
    data[8] = 0x02;
    data[9] = (uint8_t)((LOGICAL_BLOCK_SIZE >> 16) & 0xFF);
    data[10] = (uint8_t)((LOGICAL_BLOCK_SIZE >> 8) & 0xFF);
    data[11] = (uint8_t)(LOGICAL_BLOCK_SIZE & 0xFF);

    if (allocation < length) {
        length = allocation;
    }

    return send_data_in(ep_in, cbw, csw, data, length);
}

static int handle_mode_sense(
    int ep_in,
    const struct CBW *cbw,
    struct CSW *csw,
    int ten_byte
)
{
    uint8_t data[32];
    uint8_t page_control = (uint8_t)(cbw->CDB[2] >> 6);
    uint8_t page_code = cbw->CDB[2] & 0x3F;
    int changeable = page_control == 1;
    int all_pages = page_code == 0x3F;
    size_t header = ten_byte ? 8 : 4;
    size_t length = header;
    size_t allocation =
        ten_byte
            ? read_be16(&cbw->CDB[7])
            : cbw->CDB[4];

    memset(data, 0, sizeof(data));

    if (page_control == 3) {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    if (ten_byte) {
        data[3] = 0x80; /* write-protected */
    } else {
        data[2] = 0x80; /* write-protected */
    }

    /* Match Linux f_mass_storage: caching page 0x08 / all pages. */
    if (page_code == 0x08 || all_pages) {
        uint8_t *page = &data[length];
        page[0] = 0x08;
        page[1] = 10;

        if (!changeable) {
            page[2] = 0x04;
            write_be16(&page[4], 0xFFFF);
            write_be16(&page[8], 0xFFFF);
            write_be16(&page[10], 0xFFFF);
        }

        length += 12;
    } else {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    if (ten_byte) {
        write_be16(&data[0], (uint16_t)(length - 2));
    } else {
        data[0] = (uint8_t)(length - 1);
    }

    if (allocation < length) {
        length = allocation;
    }

    return send_data_in(ep_in, cbw, csw, data, length);
}

static int handle_read_header(
    int ep_in,
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t data[8];
    int msf = (cbw->CDB[1] & 0x02) != 0;
    uint32_t lba = read_be32(&cbw->CDB[2]);
    uint64_t blocks = total_blocks_for_size(iso_size);
    size_t allocation = read_be16(&cbw->CDB[7]);
    size_t length = sizeof(data);

    if ((cbw->CDB[1] & ~0x02) != 0 || lba >= blocks) {
        if (lba >= blocks) {
            sense_set_lba(
                SENSE_ILLEGAL_REQUEST,
                ASC_LOGICAL_BLOCK_OUT_OF_RANGE,
                lba
            );
        } else {
            sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        }
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    memset(data, 0, sizeof(data));
    data[0] = 0x01; /* Mode 1: 2048 bytes user data. */
    store_cdrom_address(&data[4], msf, lba);

    if (allocation < length) {
        length = allocation;
    }

    return send_data_in(ep_in, cbw, csw, data, length);
}

static int handle_read_toc(
    int ep_in,
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t data[64];
    int msf = (cbw->CDB[1] & 0x02) != 0;
    uint8_t start_track = cbw->CDB[6];
    uint8_t format = cbw->CDB[2] & 0x0F;
    uint64_t blocks64 = total_blocks_for_size(iso_size);
    uint32_t leadout = blocks64 > UINT32_MAX ? UINT32_MAX : (uint32_t)blocks64;
    size_t allocation = read_be16(&cbw->CDB[7]);
    size_t length;

    if (
        (cbw->CDB[1] & ~0x02) != 0 ||
        (start_track > 1 && format != 0x01)
    ) {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    /* Old SFF-8020i style: format in top two bits of byte 9. */
    if (format == 0) {
        uint8_t old_format = (uint8_t)((cbw->CDB[9] >> 6) & 0x03);
        if (old_format != 0) {
            format = old_format;
        }
    }

    memset(data, 0, sizeof(data));

    if (format == 0 || format == 1) {
        length = 20;
        data[1] = (uint8_t)(length - 2);
        data[2] = 1;
        data[3] = 1;

        data[5] = 0x16;
        data[6] = 0x01;
        store_cdrom_address(&data[8], msf, 0);

        data[13] = 0x16;
        data[14] = 0xAA;
        store_cdrom_address(&data[16], msf, leadout);

    } else if (format == 2) {
        int i;
        uint8_t *p;

        length = 37;
        data[1] = (uint8_t)(length - 2);
        data[2] = 1;
        data[3] = 1;

        p = &data[4];
        for (i = 0; i < 3; ++i) {
            p[0] = 1;
            p[1] = 0x16;
            p[3] = (uint8_t)(0xA0 + i);
            p[8] = 1;
            p += 11;
        }

        p -= 11;
        store_cdrom_address(&p[7], msf, leadout);

    } else {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return terminate_in_data_phase(ep_in, cbw);
    }

    if (allocation < length) {
        length = allocation;
    }

    return send_data_in(ep_in, cbw, csw, data, length);
}

static int handle_verify_10(
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint32_t lba = read_be32(&cbw->CDB[2]);
    uint16_t count = read_be16(&cbw->CDB[7]);
    uint64_t total_blocks = total_blocks_for_size(iso_size);

    /* Linux f_mass_storage supports the Windows-used BytChk=0 form. */
    if ((cbw->CDB[1] & ~0x10) != 0) {
        sense_set(SENSE_ILLEGAL_REQUEST, ASC_INVALID_FIELD_IN_CDB, 0);
        csw->Status = BOT_STATUS_FAILED;
        return 0;
    }

    if (count == 0) {
        csw->DataResidue = cbw->DataTransferLength;
        return 0;
    }

    if (
        lba >= total_blocks ||
        (uint64_t)count > total_blocks - lba
    ) {
        sense_set_lba(
            SENSE_ILLEGAL_REQUEST,
            ASC_LOGICAL_BLOCK_OUT_OF_RANGE,
            lba
        );
        csw->Status = BOT_STATUS_FAILED;
    }

    return 0;
}

static int handle_get_configuration(
    int ep_in,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t data[8];
    size_t allocation = read_be16(&cbw->CDB[7]);
    size_t length = sizeof(data);

    memset(data, 0, sizeof(data));

    /* 4 bytes follow the Data Length field; current profile = CD-ROM 0x0008. */
    write_be32(&data[0], 4);
    write_be16(&data[6], 0x0008);

    if (allocation < length) {
        length = allocation;
    }

    return send_data_in(ep_in, cbw, csw, data, length);
}

/* ------------------------------------------------------------------------- */
/* SCSI command dispatch                                                      */
/* ------------------------------------------------------------------------- */

static int handle_scsi_command(
    int ep_in,
    int ep_out,
    FILE *iso_file,
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t opcode = cbw->CDB[0];
    uint32_t expected = cbw_transfer_length(cbw);

    /* Like Linux f_mass_storage, a new non-REQUEST-SENSE command clears old
     * contingent sense before processing and replaces it if this command fails.
     */
    if (opcode != SCSI_REQUEST_SENSE) {
        sense_clear();
    }

    switch (opcode) {

        case SCSI_TEST_UNIT_READY:
        case SCSI_START_STOP_UNIT:
        case SCSI_PREVENT_ALLOW:
        case SCSI_SYNCHRONIZE_CACHE_10:
            if (expected != 0) {
                if (!cbw_is_in(cbw)) {
                    if (drain_data_out(ep_out, expected) != 0) {
                        return -1;
                    }
                } else {
                    if (terminate_in_data_phase(ep_in, cbw) != 0) {
                        return -1;
                    }
                }
                csw->Status = BOT_STATUS_PHASE_ERROR;
                csw->DataResidue = htole32(expected);
            } else {
                csw->DataResidue = htole32(0);
            }
            return 0;

        case SCSI_INQUIRY:
            return handle_inquiry(ep_in, cbw, csw);

        case SCSI_REQUEST_SENSE:
            return handle_request_sense(ep_in, cbw, csw);

        case SCSI_READ_CAPACITY_10:
            return handle_read_capacity_10(ep_in, iso_size, cbw, csw);

        case SCSI_SERVICE_ACTION_IN_16:
            if ((cbw->CDB[1] & 0x1F) == SCSI_SAI_READ_CAPACITY_16) {
                return handle_read_capacity_16(ep_in, iso_size, cbw, csw);
            }
            return fail_scsi_command(
                ep_in,
                ep_out,
                cbw,
                csw,
                SENSE_ILLEGAL_REQUEST,
                ASC_INVALID_COMMAND_OPERATION_CODE,
                0
            );

        case SCSI_READ_FORMAT_CAPACITIES:
            return handle_read_format_capacities(ep_in, iso_size, cbw, csw);

        case SCSI_MODE_SENSE_6:
            return handle_mode_sense(ep_in, cbw, csw, 0);

        case SCSI_MODE_SENSE_10:
            return handle_mode_sense(ep_in, cbw, csw, 1);

        case SCSI_READ_HEADER:
            return handle_read_header(ep_in, iso_size, cbw, csw);

        case SCSI_READ_TOC:
            return handle_read_toc(ep_in, iso_size, cbw, csw);

        case SCSI_GET_CONFIGURATION:
            return handle_get_configuration(ep_in, cbw, csw);

        case SCSI_VERIFY_10:
            return handle_verify_10(iso_size, cbw, csw);

        case SCSI_READ_6: {
            uint32_t lba =
                ((uint32_t)(cbw->CDB[1] & 0x1F) << 16) |
                ((uint32_t)cbw->CDB[2] << 8) |
                cbw->CDB[3];
            uint32_t blocks = cbw->CDB[4] == 0 ? 256u : cbw->CDB[4];

            return send_file_blocks(
                ep_in,
                iso_file,
                iso_size,
                lba,
                blocks,
                cbw,
                csw
            );
        }

        case SCSI_READ_10: {
            uint32_t lba = read_be32(&cbw->CDB[2]);
            uint16_t blocks = read_be16(&cbw->CDB[7]);

            return send_file_blocks(
                ep_in,
                iso_file,
                iso_size,
                lba,
                blocks,
                cbw,
                csw
            );
        }

        case SCSI_READ_12: {
            uint32_t lba = read_be32(&cbw->CDB[2]);
            uint32_t blocks = read_be32(&cbw->CDB[6]);

            return send_file_blocks(
                ep_in,
                iso_file,
                iso_size,
                lba,
                blocks,
                cbw,
                csw
            );
        }

        case SCSI_READ_16: {
            uint64_t lba = read_be64(&cbw->CDB[2]);
            uint32_t blocks = read_be32(&cbw->CDB[10]);

            return send_file_blocks(
                ep_in,
                iso_file,
                iso_size,
                lba,
                blocks,
                cbw,
                csw
            );
        }

        case SCSI_MODE_SELECT_6:
        case SCSI_MODE_SELECT_10:
            /* Read-only device: consume the OUT payload, then reject cleanly. */
            return fail_scsi_command(
                ep_in,
                ep_out,
                cbw,
                csw,
                SENSE_ILLEGAL_REQUEST,
                ASC_INVALID_COMMAND_OPERATION_CODE,
                0
            );

        default:
            LOGI("Unsupported SCSI opcode: 0x%02X", opcode);
            return fail_scsi_command(
                ep_in,
                ep_out,
                cbw,
                csw,
                SENSE_ILLEGAL_REQUEST,
                ASC_INVALID_COMMAND_OPERATION_CODE,
                0
            );
    }
}

/* ------------------------------------------------------------------------- */
/* Endpoint / worker lifecycle                                                */
/* ------------------------------------------------------------------------- */

static void close_data_endpoints(void)
{
    pthread_mutex_lock(&g_lock);

    if (g_ep_in >= 0) {
        close(g_ep_in);
        g_ep_in = -1;
    }

    if (g_ep_out >= 0) {
        close(g_ep_out);
        g_ep_out = -1;
    }

    pthread_mutex_unlock(&g_lock);
}

static void *scsi_thread_main(void *argument)
{
    char *iso_path = (char *)argument;
    int ep_in = -1;
    int ep_out = -1;
    FILE *iso_file = NULL;
    uint64_t iso_size;

    LOGI("SCSI thread starting for %s", iso_path);

    if (open_data_endpoints_with_retry(&ep_in, &ep_out) != 0) {
        set_last_error("FunctionFS ep1/ep2 could not be opened.");
        free(iso_path);

        pthread_mutex_lock(&g_lock);
        g_scsi_running = 0;
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    pthread_mutex_lock(&g_lock);
    g_ep_in = ep_in;
    g_ep_out = ep_out;
    pthread_mutex_unlock(&g_lock);

    iso_file = fopen(iso_path, "rb");

    if (iso_file == NULL) {
        set_last_errno("ISO file could not be opened");
        close_data_endpoints();
        free(iso_path);

        pthread_mutex_lock(&g_lock);
        g_scsi_running = 0;
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    iso_size = file_size_bytes(iso_file);

    if (iso_size == 0) {
        set_last_error("ISO file is empty or its size could not be read.");
        fclose(iso_file);
        close_data_endpoints();
        free(iso_path);

        pthread_mutex_lock(&g_lock);
        g_scsi_running = 0;
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    sense_clear();

    while (!g_stop_requested) {
        struct CBW cbw;
        struct CSW csw;
        ssize_t received;

        memset(&cbw, 0, sizeof(cbw));
        received = read_full(ep_out, &cbw, sizeof(cbw));

        if (received != (ssize_t)sizeof(cbw)) {
            if (!g_stop_requested) {
                if (received < 0) {
                    set_last_errno("FunctionFS CBW read failed");
                } else {
                    set_last_error("FunctionFS received a short CBW.");
                }
            }
            break;
        }

        memset(&csw, 0, sizeof(csw));
        csw.Signature = htole32(CSW_SIGNATURE);
        csw.Tag = cbw.Tag;
        csw.DataResidue = cbw.DataTransferLength;
        csw.Status = BOT_STATUS_GOOD;

        if (le32toh(cbw.Signature) != CBW_SIGNATURE) {
            set_last_error("Invalid USB Mass Storage CBW signature.");
            break;
        }

        if (
            (cbw.CBLength & 0xE0) != 0 ||
            (cbw.CBLength & 0x1F) == 0 ||
            (cbw.CBLength & 0x1F) > 16 ||
            (cbw.Flags & 0x7F) != 0
        ) {
            csw.Status = BOT_STATUS_PHASE_ERROR;
        } else if ((cbw.LUN & 0x0F) != 0) {
            if (
                fail_scsi_command(
                    ep_in,
                    ep_out,
                    &cbw,
                    &csw,
                    SENSE_ILLEGAL_REQUEST,
                    0x25, /* LOGICAL UNIT NOT SUPPORTED */
                    0
                ) != 0
            ) {
                break;
            }
        } else {
            if (
                handle_scsi_command(
                    ep_in,
                    ep_out,
                    iso_file,
                    iso_size,
                    &cbw,
                    &csw
                ) != 0
            ) {
                if (!g_stop_requested) {
                    set_last_errno("FunctionFS SCSI data phase failed");
                }
                break;
            }
        }

        if (
            write_full(ep_in, &csw, sizeof(csw)) !=
            (ssize_t)sizeof(csw)
        ) {
            if (!g_stop_requested) {
                set_last_errno("FunctionFS CSW write failed");
            }
            break;
        }
    }

    fclose(iso_file);
    close_data_endpoints();
    free(iso_path);

    pthread_mutex_lock(&g_lock);
    g_scsi_running = 0;
    pthread_mutex_unlock(&g_lock);

    LOGI("SCSI thread stopped");
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* FunctionFS control requests                                                */
/* ------------------------------------------------------------------------- */

static void handle_setup_event(
    int ep0,
    const struct usb_ctrlrequest *setup
)
{
    uint8_t request_type;
    uint8_t request;
    uint16_t value;
    uint16_t index;
    uint16_t length;

    if (setup == NULL) {
        return;
    }

    request_type = setup->bRequestType;
    request = setup->bRequest;
    value = le16toh(setup->wValue);
    index = le16toh(setup->wIndex);
    length = le16toh(setup->wLength);

    (void)index;

    if (
        request == BOT_REQUEST_GET_MAX_LUN &&
        (request_type & USB_DIR_IN_VALUE) != 0 &&
        (request_type & 0x60) == USB_TYPE_CLASS_VALUE &&
        (request_type & 0x1F) == USB_RECIP_INTERFACE_VALUE &&
        value == 0
    ) {
        uint8_t max_lun = 0;
        if (length > 0) {
            (void)write(ep0, &max_lun, 1);
        }
        return;
    }

    if (
        request == BOT_REQUEST_RESET &&
        (request_type & USB_DIR_IN_VALUE) == 0 &&
        (request_type & 0x60) == USB_TYPE_CLASS_VALUE &&
        (request_type & 0x1F) == USB_RECIP_INTERFACE_VALUE &&
        value == 0 &&
        length == 0
    ) {
        uint8_t dummy = 0;
        sense_clear();
        (void)read(ep0, &dummy, 0);
        return;
    }

    /* Stall unsupported setup requests using FunctionFS wrong-direction rule. */
    if (request_type & USB_DIR_IN_VALUE) {
        uint8_t dummy = 0;
        (void)read(ep0, &dummy, 0);
    } else {
        uint8_t dummy = 0;
        (void)write(ep0, &dummy, 0);
    }
}

/* ------------------------------------------------------------------------- */
/* Event thread                                                               */
/* ------------------------------------------------------------------------- */

static int start_scsi_thread_if_needed(void)
{
    char *path_copy;

    pthread_mutex_lock(&g_lock);

    if (
        g_stop_requested ||
        g_scsi_running ||
        g_scsi_thread_created ||
        g_iso_path == NULL
    ) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }

    path_copy = strdup(g_iso_path);
    if (path_copy == NULL) {
        pthread_mutex_unlock(&g_lock);
        set_last_error("Out of memory while starting SCSI thread.");
        return -1;
    }

    /* Set before pthread_create to avoid ENABLE/join race. */
    g_scsi_running = 1;

    if (
        pthread_create(
            &g_scsi_thread,
            NULL,
            scsi_thread_main,
            path_copy
        ) != 0
    ) {
        g_scsi_running = 0;
        free(path_copy);
        pthread_mutex_unlock(&g_lock);
        set_last_error("pthread_create() failed for SCSI thread.");
        return -1;
    }

    g_scsi_thread_created = 1;
    pthread_mutex_unlock(&g_lock);
    return 0;
}

static void join_finished_scsi_thread(void)
{
    pthread_t thread;
    int should_join = 0;

    pthread_mutex_lock(&g_lock);

    if (g_scsi_thread_created && !g_scsi_running) {
        thread = g_scsi_thread;
        g_scsi_thread_created = 0;
        should_join = 1;
    }

    pthread_mutex_unlock(&g_lock);

    if (should_join) {
        (void)pthread_join(thread, NULL);
    }
}

static void *event_thread_main(void *unused)
{
    int ep0;

    (void)unused;

    pthread_mutex_lock(&g_lock);
    ep0 = g_ep0;
    pthread_mutex_unlock(&g_lock);

    while (!g_stop_requested) {
        struct pollfd pfd;
        struct usb_functionfs_event event;
        int poll_result;
        ssize_t result;

        memset(&pfd, 0, sizeof(pfd));
        pfd.fd = ep0;
        pfd.events = POLLIN | POLLERR | POLLHUP;

        poll_result = poll(&pfd, 1, 200);

        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            if (!g_stop_requested) {
                set_last_errno("FunctionFS ep0 poll failed");
            }
            break;
        }

        if (poll_result == 0) {
            continue;
        }

        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            if (!g_stop_requested) {
                set_last_error("FunctionFS ep0 was disconnected.");
            }
            break;
        }

        if (!(pfd.revents & POLLIN)) {
            continue;
        }

        result = read_full(ep0, &event, sizeof(event));

        if (result != (ssize_t)sizeof(event)) {
            if (!g_stop_requested) {
                set_last_errno("FunctionFS ep0 event read failed");
            }
            break;
        }

        switch (event.type) {
            case FUNCTIONFS_BIND:
                LOGI("FUNCTIONFS_BIND");
                break;

            case FUNCTIONFS_UNBIND:
                LOGI("FUNCTIONFS_UNBIND");
                close_data_endpoints();
                break;

            case FUNCTIONFS_ENABLE:
                LOGI("FUNCTIONFS_ENABLE");
                join_finished_scsi_thread();
                if (start_scsi_thread_if_needed() != 0) {
                    g_stop_requested = 1;
                }
                break;

            case FUNCTIONFS_DISABLE:
                LOGI("FUNCTIONFS_DISABLE");
                close_data_endpoints();
                break;

            case FUNCTIONFS_SETUP:
                handle_setup_event(g_ep0, &event.u.setup);
                break;

            case FUNCTIONFS_SUSPEND:
                LOGI("FUNCTIONFS_SUSPEND");
                break;

            case FUNCTIONFS_RESUME:
                LOGI("FUNCTIONFS_RESUME");
                break;

            default:
                LOGI("Unknown FunctionFS event: %u", event.type);
                break;
        }
    }

    close_data_endpoints();

    pthread_mutex_lock(&g_lock);
    g_running = 0;
    pthread_mutex_unlock(&g_lock);

    LOGI("FunctionFS event thread stopped");
    return NULL;
}

/* ------------------------------------------------------------------------- */
/* Internal start / stop                                                      */
/* ------------------------------------------------------------------------- */

static int functionfs_start(const char *iso_path)
{
    int ep0;
    struct stat file_info;
    char *path_copy;

    if (iso_path == NULL || iso_path[0] == '\0') {
        set_last_error("ISO path is empty.");
        return -1;
    }

    if (
        stat(iso_path, &file_info) != 0 ||
        !S_ISREG(file_info.st_mode)
    ) {
        set_last_error("ISO path is not a regular file.");
        return -1;
    }

    if (access(iso_path, R_OK) != 0) {
        set_last_errno("ISO file is not readable");
        return -1;
    }

    if (prepare_functionfs_mount() != 0) {
        return -1;
    }

    pthread_mutex_lock(&g_lock);
    if (g_running || g_event_thread_created) {
        pthread_mutex_unlock(&g_lock);
        set_last_error("FunctionFS backend is already running.");
        return -1;
    }
    pthread_mutex_unlock(&g_lock);

    ep0 = open(EP0_PATH, O_RDWR | O_CLOEXEC);
    if (ep0 < 0) {
        set_last_errno("FunctionFS ep0 could not be opened");
        return -1;
    }

    if (
        write_full(ep0, &descriptors, sizeof(descriptors)) !=
        (ssize_t)sizeof(descriptors)
    ) {
        set_last_errno("Could not write FunctionFS descriptors");
        close(ep0);
        return -1;
    }

    if (
        write_full(ep0, &strings, sizeof(strings)) !=
        (ssize_t)sizeof(strings)
    ) {
        set_last_errno("Could not write FunctionFS strings");
        close(ep0);
        return -1;
    }

    path_copy = strdup(iso_path);
    if (path_copy == NULL) {
        close(ep0);
        set_last_error("Out of memory while saving ISO path.");
        return -1;
    }

    pthread_mutex_lock(&g_lock);
    g_stop_requested = 0;
    g_running = 1;
    g_scsi_running = 0;
    g_ep0 = ep0;
    g_ep_in = -1;
    g_ep_out = -1;
    g_iso_path = path_copy;
    g_last_error[0] = '\0';
    sense_clear();

    if (
        pthread_create(
            &g_event_thread,
            NULL,
            event_thread_main,
            NULL
        ) != 0
    ) {
        g_running = 0;
        g_ep0 = -1;
        g_iso_path = NULL;
        pthread_mutex_unlock(&g_lock);

        close(ep0);
        free(path_copy);
        set_last_error("pthread_create() failed for FunctionFS event thread.");
        return -1;
    }

    g_event_thread_created = 1;
    pthread_mutex_unlock(&g_lock);

    LOGI("FunctionFS backend started; waiting for UDC bind/ENABLE");
    return 0;
}

static int functionfs_stop(void)
{
    pthread_t event_thread;
    pthread_t scsi_thread;
    int join_event = 0;
    int join_scsi = 0;
    int ep0 = -1;
    char *path = NULL;

    pthread_mutex_lock(&g_lock);

    if (
        !g_running &&
        !g_event_thread_created &&
        !g_scsi_thread_created
    ) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }

    g_stop_requested = 1;

    if (g_event_thread_created) {
        event_thread = g_event_thread;
        g_event_thread_created = 0;
        join_event = 1;
    }

    ep0 = g_ep0;
    g_ep0 = -1;

    pthread_mutex_unlock(&g_lock);

    close_data_endpoints();

    if (ep0 >= 0) {
        close(ep0);
    }

    if (join_event) {
        (void)pthread_join(event_thread, NULL);
    }

    pthread_mutex_lock(&g_lock);

    if (g_scsi_thread_created) {
        scsi_thread = g_scsi_thread;
        g_scsi_thread_created = 0;
        join_scsi = 1;
    }

    pthread_mutex_unlock(&g_lock);

    if (join_scsi) {
        (void)pthread_join(scsi_thread, NULL);
    }

    pthread_mutex_lock(&g_lock);

    path = g_iso_path;
    g_iso_path = NULL;
    g_running = 0;
    g_scsi_running = 0;
    g_stop_requested = 0;
    g_ep_in = -1;
    g_ep_out = -1;
    sense_clear();

    pthread_mutex_unlock(&g_lock);

    free(path);

    LOGI("FunctionFS backend stopped");
    return 0;
}

/* ------------------------------------------------------------------------- */
/* JNI                                                                        */
/* ------------------------------------------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_prepareNative(
    JNIEnv *env,
    jclass clazz
)
{
    (void)env;
    (void)clazz;

    set_last_error(NULL);

    return
        prepare_functionfs_mount() == 0
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_startNative(
    JNIEnv *env,
    jclass clazz,
    jstring isoPath
)
{
    const char *path;
    int result;

    (void)clazz;

    if (isoPath == NULL) {
        set_last_error("ISO path is null.");
        return JNI_FALSE;
    }

    path = (*env)->GetStringUTFChars(env, isoPath, NULL);
    if (path == NULL) {
        set_last_error("Could not read ISO path from Java.");
        return JNI_FALSE;
    }

    set_last_error(NULL);
    result = functionfs_start(path);

    (*env)->ReleaseStringUTFChars(env, isoPath, path);

    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_stopNative(
    JNIEnv *env,
    jclass clazz
)
{
    (void)env;
    (void)clazz;

    return functionfs_stop() == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_isRunningNative(
    JNIEnv *env,
    jclass clazz
)
{
    int running;

    (void)env;
    (void)clazz;

    pthread_mutex_lock(&g_lock);
    running = g_running;
    pthread_mutex_unlock(&g_lock);

    return running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_getLastErrorNative(
    JNIEnv *env,
    jclass clazz
)
{
    char buffer[sizeof(g_last_error)];

    (void)clazz;

    pthread_mutex_lock(&g_lock);
    snprintf(buffer, sizeof(buffer), "%s", g_last_error);
    pthread_mutex_unlock(&g_lock);

    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    (void)functionfs_stop();
}
