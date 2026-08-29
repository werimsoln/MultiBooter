/******************************************************************************
 * libfunctionfs.c
 *
 * Copyright (c) 2026, werismoln <vlkanblek@gmail.com>
 *
 * GPLv3+
 *
 * MultiBooter FunctionFS backend as a JNI shared library.
 *
 * This replaces the former standalone:
 *
 *     ffs_gadget <ISO_PATH>
 *
 * design with:
 *
 *     System.loadLibrary("functionfs")
 *         -> FunctionFileSystem.startNative()
 *         -> native FunctionFS event thread
 *         -> native USB Mass Storage BOT/SCSI thread
 *
 ******************************************************************************/

#include <jni.h>

#include <android/log.h>
#include <endian.h>
#include <errno.h>
#include <fcntl.h>
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
#define USB_CLASS_MASS_STORAGE        0x08
#endif

#define SCSI_TRANSPARENT_SUBCLASS     0x06
#define BULK_ONLY_TRANSPORT_PROTOCOL  0x50

#define USB_DIR_IN_VALUE  0x80
#define USB_DIR_OUT_VALUE 0x00

#define USB_TYPE_CLASS_VALUE     0x20
#define USB_RECIP_INTERFACE_VALUE 0x01

#define BOT_REQUEST_RESET        0xFF
#define BOT_REQUEST_GET_MAX_LUN  0xFE

#define CBW_SIGNATURE 0x43425355u
#define CSW_SIGNATURE 0x53425355u

/*
 * This FunctionFS mode presents an optical/CD-ROM-like device.
 * SCSI optical media normally use 2048-byte logical blocks.
 */
#define LOGICAL_BLOCK_SIZE 2048u

#define SCSI_TEST_UNIT_READY        0x00
#define SCSI_REQUEST_SENSE          0x03
#define SCSI_INQUIRY                0x12
#define SCSI_MODE_SENSE_6           0x1A
#define SCSI_START_STOP_UNIT        0x1B
#define SCSI_PREVENT_ALLOW          0x1E
#define SCSI_READ_CAPACITY_10       0x25
#define SCSI_READ_10                0x28
#define SCSI_SYNCHRONIZE_CACHE_10   0x35
#define SCSI_MODE_SENSE_10          0x5A

#define SCSI_STATUS_GOOD   0
#define SCSI_STATUS_FAILED 1


/*
 * Static initializers require compile-time endian conversion. Android
 * arm64-v8a is little-endian, but keep the source correct on either endian.
 */
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

/*
 * --------------------------------------------------------------------------
 * FunctionFS descriptors
 * --------------------------------------------------------------------------
 */

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
            .bDescriptorType    = 4,
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
            .bDescriptorType  = 5,
            .bEndpointAddress = 1 | USB_DIR_IN_VALUE,
            .bmAttributes     = 2,
            .wMaxPacketSize   = CONST_LE16(64),
            .bInterval        = 0,
        },

        .ep_out = {
            .bLength          = sizeof(struct usb_endpoint_descriptor_no_audio),
            .bDescriptorType  = 5,
            .bEndpointAddress = 2 | USB_DIR_OUT_VALUE,
            .bmAttributes     = 2,
            .wMaxPacketSize   = CONST_LE16(64),
            .bInterval        = 0,
        },
    },

    .hs_descs = {

        .intf = {
            .bLength            = sizeof(struct usb_interface_descriptor),
            .bDescriptorType    = 4,
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
            .bDescriptorType  = 5,
            .bEndpointAddress = 1 | USB_DIR_IN_VALUE,
            .bmAttributes     = 2,
            .wMaxPacketSize   = CONST_LE16(512),
            .bInterval        = 0,
        },

        .ep_out = {
            .bLength          = sizeof(struct usb_endpoint_descriptor_no_audio),
            .bDescriptorType  = 5,
            .bEndpointAddress = 2 | USB_DIR_OUT_VALUE,
            .bmAttributes     = 2,
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

/*
 * --------------------------------------------------------------------------
 * Global state
 * --------------------------------------------------------------------------
 */

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

/*
 * --------------------------------------------------------------------------
 * Utility
 * --------------------------------------------------------------------------
 */

static void set_last_error(const char *message)
{
    pthread_mutex_lock(&g_lock);

    if (message == NULL) {
        g_last_error[0] = '\0';
    } else {
        snprintf(
            g_last_error,
            sizeof(g_last_error),
            "%s",
            message
        );
    }

    pthread_mutex_unlock(&g_lock);
}

static void set_last_errno(const char *prefix)
{
    char buffer[512];

    snprintf(
        buffer,
        sizeof(buffer),
        "%s: %s",
        prefix,
        strerror(errno)
    );

    set_last_error(buffer);
}

static ssize_t read_full(
    int fd,
    void *buffer,
    size_t length
)
{
    uint8_t *cursor = (uint8_t *)buffer;
    size_t done = 0;

    while (done < length) {

        ssize_t result = read(
            fd,
            cursor + done,
            length - done
        );

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

static ssize_t write_full(
    int fd,
    const void *buffer,
    size_t length
)
{
    const uint8_t *cursor = (const uint8_t *)buffer;
    size_t done = 0;

    while (done < length) {

        ssize_t result = write(
            fd,
            cursor + done,
            length - done
        );

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

static uint32_t read_be32(
    const uint8_t *data
)
{
    return
        ((uint32_t)data[0] << 24) |
        ((uint32_t)data[1] << 16) |
        ((uint32_t)data[2] << 8)  |
        ((uint32_t)data[3]);
}

static uint16_t read_be16(
    const uint8_t *data
)
{
    return
        (uint16_t)(
            ((uint16_t)data[0] << 8) |
            ((uint16_t)data[1])
        );
}

static void write_be32(
    uint8_t *data,
    uint32_t value
)
{
    data[0] = (uint8_t)((value >> 24) & 0xFF);
    data[1] = (uint8_t)((value >> 16) & 0xFF);
    data[2] = (uint8_t)((value >> 8) & 0xFF);
    data[3] = (uint8_t)(value & 0xFF);
}

static uint64_t file_size_bytes(
    FILE *file
)
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

    (void)fseeko(
        file,
        original,
        SEEK_SET
    );

    if (end < 0) {
        return 0;
    }

    return (uint64_t)end;
}

static int run_root_command(
    const char *command
)
{
    char shell[2048];
    int result;

    if (command == NULL) {
        return -1;
    }

    /*
     * The command itself contains only values generated by this library:
     * fixed paths plus numeric uid/gid.
     */
    snprintf(
        shell,
        sizeof(shell),
        "su -c '%s'",
        command
    );

    result = system(shell);

    return result == 0
        ? 0
        : -1;
}

static int is_functionfs_mounted(void)
{
    FILE *mounts;
    char line[1024];

    mounts = fopen(
        "/proc/mounts",
        "r"
    );

    if (mounts == NULL) {
        return 0;
    }

    while (
        fgets(
            line,
            sizeof(line),
            mounts
        ) != NULL
    ) {

        if (
            strstr(
                line,
                " " FFS_DIR " "
            ) != NULL &&
            strstr(
                line,
                " functionfs "
            ) != NULL
        ) {

            fclose(mounts);
            return 1;
        }
    }

    fclose(mounts);
    return 0;
}

/*
 * The shared library remains inside the Android app process and therefore
 * keeps the app UID. Calling "su -c" does NOT elevate this process.
 *
 * We instead mount FunctionFS with endpoint ownership assigned to this app
 * UID/GID, allowing the JNI threads to access ep0/ep1/ep2.
 */
static int prepare_functionfs_mount(void)
{
    uid_t uid = getuid();
    gid_t gid = getgid();

    char command[1024];

    if (
        access(
            EP0_PATH,
            R_OK | W_OK
        ) == 0
    ) {
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

        if (
            run_root_command(
                command
            ) != 0
        ) {

            set_last_error(
                "Could not mount FunctionFS through root."
            );

            return -1;
        }
    }

    /*
     * If it was already mounted with incompatible ownership, try to make
     * ep0 accessible. Newly-created ep1/ep2 should normally inherit mount
     * ownership/mode.
     */
    if (
        access(
            EP0_PATH,
            R_OK | W_OK
        ) != 0
    ) {

        snprintf(
            command,
            sizeof(command),
            "chown %u:%u " EP0_PATH
            " && chmod 0660 " EP0_PATH,
            (unsigned int)uid,
            (unsigned int)gid
        );

        if (
            run_root_command(
                command
            ) != 0
        ) {

            set_last_error(
                "FunctionFS ep0 exists but is not accessible to the app."
            );

            return -1;
        }
    }

    if (
        access(
            EP0_PATH,
            R_OK | W_OK
        ) != 0
    ) {

        set_last_error(
            "FunctionFS ep0 is still inaccessible after root preparation."
        );

        return -1;
    }

    return 0;
}

static int open_data_endpoints_with_retry(
    int *ep_in,
    int *ep_out
)
{
    int attempt;

    if (
        ep_in == NULL ||
        ep_out == NULL
    ) {
        return -1;
    }

    *ep_in = -1;
    *ep_out = -1;

    for (
        attempt = 0;
        attempt < 100 &&
        !g_stop_requested;
        ++attempt
    ) {

        int in_fd = open(
            EP1_PATH,
            O_RDWR
        );

        int out_fd = open(
            EP2_PATH,
            O_RDWR
        );

        if (
            in_fd >= 0 &&
            out_fd >= 0
        ) {

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

        /*
         * When FunctionFS had already been mounted by another process,
         * ep1/ep2 may inherit ownership that the app cannot use. Once the
         * nodes appear, ask root to hand only these endpoints to this UID.
         */
        if (
            attempt == 10 &&
            (
                access(EP1_PATH, F_OK) == 0 ||
                access(EP2_PATH, F_OK) == 0
            )
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

            (void)run_root_command(
                command
            );
        }

        usleep(20 * 1000);
    }

    return -1;
}

static int send_data_in(
    int fd,
    const struct CBW *cbw,
    struct CSW *csw,
    const void *data,
    size_t data_length
)
{
    uint32_t expected;
    size_t send_length;

    if (
        cbw == NULL ||
        csw == NULL
    ) {
        return -1;
    }

    expected =
        le32toh(
            cbw->DataTransferLength
        );

    send_length =
        data_length;

    if (
        send_length >
        expected
    ) {
        send_length =
            expected;
    }

    if (
        send_length > 0 &&
        write_full(
            fd,
            data,
            send_length
        ) != (ssize_t)send_length
    ) {
        return -1;
    }

    csw->DataResidue =
        htole32(
            expected -
            (uint32_t)send_length
        );

    return 0;
}

/*
 * --------------------------------------------------------------------------
 * SCSI
 * --------------------------------------------------------------------------
 */

static int handle_scsi_command(
    int ep_in,
    FILE *iso_file,
    uint64_t iso_size,
    const struct CBW *cbw,
    struct CSW *csw
)
{
    uint8_t opcode;
    uint64_t total_blocks;

    opcode =
        cbw->CDB[0];

    total_blocks =
        (
            iso_size +
            LOGICAL_BLOCK_SIZE -
            1
        ) /
        LOGICAL_BLOCK_SIZE;

    switch (opcode) {

        case SCSI_TEST_UNIT_READY:
        case SCSI_START_STOP_UNIT:
        case SCSI_PREVENT_ALLOW:
        case SCSI_SYNCHRONIZE_CACHE_10:

            csw->DataResidue =
                htole32(0);

            return 0;

        case SCSI_INQUIRY: {

            static const uint8_t inquiry[36] = {

                /* Peripheral device type: CD/DVD */
                0x05,

                /* Removable */
                0x80,

                /* SPC-2 */
                0x02,

                0x02,

                /* Additional length */
                31,

                0x00,
                0x00,
                0x00,

                /* Vendor: 8 bytes */
                'M','U','L','T','I','B','O','T',

                /* Product: 16 bytes */
                'V','i','r','t','u','a','l',' ',
                'C','D','-','R','O','M',' ',' ',

                /* Revision: 4 bytes */
                '1','.','0','0'
            };

            size_t requested =
                cbw->CDB[4];

            size_t available =
                sizeof(inquiry);

            if (
                requested <
                available
            ) {
                available =
                    requested;
            }

            return send_data_in(
                ep_in,
                cbw,
                csw,
                inquiry,
                available
            );
        }

        case SCSI_REQUEST_SENSE: {

            uint8_t sense[18];

            memset(
                sense,
                0,
                sizeof(sense)
            );

            /*
             * Fixed format, current errors.
             * Sense key = NO SENSE.
             */
            sense[0] = 0x70;
            sense[7] = 10;

            return send_data_in(
                ep_in,
                cbw,
                csw,
                sense,
                sizeof(sense)
            );
        }

        case SCSI_READ_CAPACITY_10: {

            uint8_t capacity[8];

            uint32_t last_lba;

            memset(
                capacity,
                0,
                sizeof(capacity)
            );

            if (total_blocks == 0) {
                last_lba = 0;
            } else if (
                total_blocks - 1 >
                UINT32_MAX
            ) {
                last_lba =
                    UINT32_MAX;
            } else {
                last_lba =
                    (uint32_t)(
                        total_blocks - 1
                    );
            }

            write_be32(
                capacity,
                last_lba
            );

            write_be32(
                capacity + 4,
                LOGICAL_BLOCK_SIZE
            );

            return send_data_in(
                ep_in,
                cbw,
                csw,
                capacity,
                sizeof(capacity)
            );
        }

        case SCSI_MODE_SENSE_6: {

            uint8_t mode[4];

            memset(
                mode,
                0,
                sizeof(mode)
            );

            mode[0] = 3;

            /*
             * Device-specific parameter:
             * write protected.
             */
            mode[2] = 0x80;

            return send_data_in(
                ep_in,
                cbw,
                csw,
                mode,
                sizeof(mode)
            );
        }

        case SCSI_MODE_SENSE_10: {

            uint8_t mode[8];

            memset(
                mode,
                0,
                sizeof(mode)
            );

            mode[1] = 6;
            mode[3] = 0x80;

            return send_data_in(
                ep_in,
                cbw,
                csw,
                mode,
                sizeof(mode)
            );
        }

        case SCSI_READ_10: {

            uint32_t start_lba;
            uint16_t blocks;

            uint64_t offset;
            uint64_t requested_bytes_64;

            uint32_t expected;

            uint8_t *buffer;
            size_t requested_bytes;
            size_t bytes_read;

            start_lba =
                read_be32(
                    &cbw->CDB[2]
                );

            blocks =
                read_be16(
                    &cbw->CDB[7]
                );

            if (blocks == 0) {

                csw->DataResidue =
                    htole32(0);

                return 0;
            }

            requested_bytes_64 =
                (uint64_t)blocks *
                LOGICAL_BLOCK_SIZE;

            if (
                requested_bytes_64 >
                SIZE_MAX
            ) {
                return -1;
            }

            requested_bytes =
                (size_t)
                requested_bytes_64;

            offset =
                (uint64_t)start_lba *
                LOGICAL_BLOCK_SIZE;

            expected =
                le32toh(
                    cbw->DataTransferLength
                );

            if (
                requested_bytes >
                expected
            ) {
                requested_bytes =
                    expected;
            }

            if (
                offset >
                iso_size
            ) {

                csw->Status =
                    SCSI_STATUS_FAILED;

                return 0;
            }

            buffer =
                (uint8_t *)calloc(
                    1,
                    requested_bytes
                );

            if (
                buffer == NULL &&
                requested_bytes != 0
            ) {
                return -1;
            }

            if (
                fseeko(
                    iso_file,
                    (off_t)offset,
                    SEEK_SET
                ) != 0
            ) {

                free(buffer);
                return -1;
            }

            bytes_read =
                fread(
                    buffer,
                    1,
                    requested_bytes,
                    iso_file
                );

            /*
             * Remaining bytes stay zero-filled. This cleanly pads the final
             * 2048-byte logical block when the ISO size is not aligned.
             */
            if (
                send_data_in(
                    ep_in,
                    cbw,
                    csw,
                    buffer,
                    requested_bytes
                ) != 0
            ) {

                free(buffer);
                return -1;
            }

            (void)bytes_read;

            free(buffer);
            return 0;
        }

        default:

            LOGI(
                "Unsupported SCSI opcode: 0x%02X",
                opcode
            );

            /*
             * If the host expected an IN data phase, terminate that phase
             * with a zero-length packet before returning a failed CSW.
             * Otherwise the CSW itself could be mistaken for command data.
             */
            if (
                (
                    cbw->Flags &
                    USB_DIR_IN_VALUE
                ) != 0 &&
                le32toh(
                    cbw->DataTransferLength
                ) > 0
            ) {

                (void)write(
                    ep_in,
                    NULL,
                    0
                );
            }

            csw->Status =
                SCSI_STATUS_FAILED;

            return 0;
    }
}

static void close_data_endpoints(void)
{
    pthread_mutex_lock(
        &g_lock
    );

    if (g_ep_in >= 0) {
        close(g_ep_in);
        g_ep_in = -1;
    }

    if (g_ep_out >= 0) {
        close(g_ep_out);
        g_ep_out = -1;
    }

    pthread_mutex_unlock(
        &g_lock
    );
}

static void *scsi_thread_main(
    void *argument
)
{
    char *iso_path =
        (char *)argument;

    int ep_in = -1;
    int ep_out = -1;

    FILE *iso_file = NULL;

    uint64_t iso_size;

    LOGI(
        "SCSI thread starting for %s",
        iso_path
    );

    if (
        open_data_endpoints_with_retry(
            &ep_in,
            &ep_out
        ) != 0
    ) {

        set_last_error(
            "FunctionFS ep1/ep2 could not be opened."
        );

        free(iso_path);

        pthread_mutex_lock(
            &g_lock
        );

        g_scsi_running = 0;

        pthread_mutex_unlock(
            &g_lock
        );

        return NULL;
    }

    pthread_mutex_lock(
        &g_lock
    );

    g_ep_in = ep_in;
    g_ep_out = ep_out;
    g_scsi_running = 1;

    pthread_mutex_unlock(
        &g_lock
    );

    iso_file =
        fopen(
            iso_path,
            "rb"
        );

    if (iso_file == NULL) {

        set_last_errno(
            "ISO file could not be opened"
        );

        close_data_endpoints();
        free(iso_path);

        pthread_mutex_lock(
            &g_lock
        );

        g_scsi_running = 0;

        pthread_mutex_unlock(
            &g_lock
        );

        return NULL;
    }

    iso_size =
        file_size_bytes(
            iso_file
        );

    while (!g_stop_requested) {

        struct CBW cbw;
        struct CSW csw;

        ssize_t received =
            read_full(
                ep_out,
                &cbw,
                sizeof(cbw)
            );

        if (
            received !=
            (ssize_t)sizeof(cbw)
        ) {

            if (!g_stop_requested) {

                set_last_errno(
                    "FunctionFS CBW read failed"
                );
            }

            break;
        }

        memset(
            &csw,
            0,
            sizeof(csw)
        );

        csw.Signature =
            htole32(
                CSW_SIGNATURE
            );

        csw.Tag =
            cbw.Tag;

        csw.DataResidue =
            cbw.DataTransferLength;

        csw.Status =
            SCSI_STATUS_GOOD;

        if (
            le32toh(
                cbw.Signature
            ) != CBW_SIGNATURE
        ) {

            csw.Status =
                SCSI_STATUS_FAILED;

        } else if (
            cbw.CBLength == 0 ||
            cbw.CBLength > 16
        ) {

            csw.Status =
                SCSI_STATUS_FAILED;

        } else {

            if (
                handle_scsi_command(
                    ep_in,
                    iso_file,
                    iso_size,
                    &cbw,
                    &csw
                ) != 0
            ) {

                csw.Status =
                    SCSI_STATUS_FAILED;
            }
        }

        if (
            write_full(
                ep_in,
                &csw,
                sizeof(csw)
            ) != (ssize_t)sizeof(csw)
        ) {

            if (!g_stop_requested) {

                set_last_errno(
                    "FunctionFS CSW write failed"
                );
            }

            break;
        }
    }

    fclose(
        iso_file
    );

    close_data_endpoints();

    free(
        iso_path
    );

    pthread_mutex_lock(
        &g_lock
    );

    g_scsi_running = 0;

    pthread_mutex_unlock(
        &g_lock
    );

    LOGI(
        "SCSI thread stopped"
    );

    return NULL;
}

/*
 * --------------------------------------------------------------------------
 * FunctionFS control requests
 * --------------------------------------------------------------------------
 */

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

    request_type =
        setup->bRequestType;

    request =
        setup->bRequest;

    value =
        le16toh(
            setup->wValue
        );

    index =
        le16toh(
            setup->wIndex
        );

    length =
        le16toh(
            setup->wLength
        );

    (void)value;
    (void)index;

    /*
     * Mass Storage GET_MAX_LUN:
     * Device -> Host, Class, Interface, bRequest=FEh, one byte.
     */
    if (
        request ==
            BOT_REQUEST_GET_MAX_LUN &&
        (
            request_type &
            USB_DIR_IN_VALUE
        ) != 0 &&
        (
            request_type &
            0x60
        ) == USB_TYPE_CLASS_VALUE &&
        (
            request_type &
            0x1F
        ) == USB_RECIP_INTERFACE_VALUE
    ) {

        uint8_t max_lun = 0;

        if (length > 0) {

            (void)write(
                ep0,
                &max_lun,
                1
            );
        }

        return;
    }

    /*
     * Mass Storage Bulk-Only Reset:
     * Host -> Device, Class, Interface, bRequest=FFh, no data phase.
     */
    if (
        request ==
            BOT_REQUEST_RESET &&
        (
            request_type &
            USB_DIR_IN_VALUE
        ) == 0 &&
        (
            request_type &
            0x60
        ) == USB_TYPE_CLASS_VALUE &&
        (
            request_type &
            0x1F
        ) == USB_RECIP_INTERFACE_VALUE
    ) {

        /*
         * For a zero-length OUT setup request FunctionFS completes the
         * transaction when userspace consumes the zero-length data phase.
         */
        uint8_t dummy = 0;

        (void)read(
            ep0,
            &dummy,
            0
        );

        return;
    }

    /*
     * Stall unsupported class/vendor requests by deliberately issuing the
     * data-stage operation in the wrong direction, as documented by
     * FunctionFS.
     */
    if (
        request_type &
        USB_DIR_IN_VALUE
    ) {

        uint8_t dummy = 0;

        (void)read(
            ep0,
            &dummy,
            0
        );

    } else {

        uint8_t dummy = 0;

        (void)write(
            ep0,
            &dummy,
            0
        );
    }
}

/*
 * --------------------------------------------------------------------------
 * Event thread
 * --------------------------------------------------------------------------
 */

static int start_scsi_thread_if_needed(void)
{
    char *path_copy;

    pthread_mutex_lock(
        &g_lock
    );

    if (
        g_stop_requested ||
        g_scsi_running ||
        g_scsi_thread_created ||
        g_iso_path == NULL
    ) {

        pthread_mutex_unlock(
            &g_lock
        );

        return 0;
    }

    path_copy =
        strdup(
            g_iso_path
        );

    if (path_copy == NULL) {

        pthread_mutex_unlock(
            &g_lock
        );

        set_last_error(
            "Out of memory while starting SCSI thread."
        );

        return -1;
    }

    if (
        pthread_create(
            &g_scsi_thread,
            NULL,
            scsi_thread_main,
            path_copy
        ) != 0
    ) {

        free(
            path_copy
        );

        pthread_mutex_unlock(
            &g_lock
        );

        set_last_error(
            "pthread_create() failed for SCSI thread."
        );

        return -1;
    }

    g_scsi_thread_created = 1;

    pthread_mutex_unlock(
        &g_lock
    );

    return 0;
}

static void join_finished_scsi_thread(void)
{
    pthread_t thread;
    int should_join = 0;

    pthread_mutex_lock(
        &g_lock
    );

    if (
        g_scsi_thread_created &&
        !g_scsi_running
    ) {

        thread =
            g_scsi_thread;

        g_scsi_thread_created = 0;

        should_join = 1;
    }

    pthread_mutex_unlock(
        &g_lock
    );

    if (should_join) {

        (void)pthread_join(
            thread,
            NULL
        );
    }
}

static void *event_thread_main(
    void *unused
)
{
    int ep0;

    (void)unused;

    pthread_mutex_lock(
        &g_lock
    );

    ep0 =
        g_ep0;

    pthread_mutex_unlock(
        &g_lock
    );

    while (!g_stop_requested) {

        struct pollfd pfd;
        struct usb_functionfs_event event;

        int poll_result;
        ssize_t result;

        memset(
            &pfd,
            0,
            sizeof(pfd)
        );

        pfd.fd =
            ep0;

        pfd.events =
            POLLIN | POLLERR | POLLHUP;

        poll_result =
            poll(
                &pfd,
                1,
                200
            );

        if (poll_result < 0) {

            if (errno == EINTR) {
                continue;
            }

            if (!g_stop_requested) {

                set_last_errno(
                    "FunctionFS ep0 poll failed"
                );
            }

            break;
        }

        if (poll_result == 0) {
            continue;
        }

        if (
            pfd.revents &
            (POLLERR | POLLHUP | POLLNVAL)
        ) {

            if (!g_stop_requested) {

                set_last_error(
                    "FunctionFS ep0 was disconnected."
                );
            }

            break;
        }

        if (
            !(pfd.revents & POLLIN)
        ) {
            continue;
        }

        result =
            read_full(
                ep0,
                &event,
                sizeof(event)
            );

        if (
            result !=
            (ssize_t)sizeof(event)
        ) {

            if (!g_stop_requested) {

                set_last_errno(
                    "FunctionFS ep0 event read failed"
                );
            }

            break;
        }

        switch (event.type) {

            case FUNCTIONFS_BIND:

                LOGI(
                    "FUNCTIONFS_BIND"
                );

                break;

            case FUNCTIONFS_UNBIND:

                LOGI(
                    "FUNCTIONFS_UNBIND"
                );

                close_data_endpoints();

                break;

            case FUNCTIONFS_ENABLE:

                LOGI(
                    "FUNCTIONFS_ENABLE"
                );

                join_finished_scsi_thread();

                if (
                    start_scsi_thread_if_needed() != 0
                ) {

                    g_stop_requested = 1;
                }

                break;

            case FUNCTIONFS_DISABLE:

                LOGI(
                    "FUNCTIONFS_DISABLE"
                );

                close_data_endpoints();

                break;

            case FUNCTIONFS_SETUP:

                handle_setup_event(
                    g_ep0,
                    &event.u.setup
                );

                break;

            case FUNCTIONFS_SUSPEND:

                LOGI(
                    "FUNCTIONFS_SUSPEND"
                );

                break;

            case FUNCTIONFS_RESUME:

                LOGI(
                    "FUNCTIONFS_RESUME"
                );

                break;

            default:

                LOGI(
                    "Unknown FunctionFS event: %u",
                    event.type
                );

                break;
        }
    }

    close_data_endpoints();

    pthread_mutex_lock(
        &g_lock
    );

    g_running = 0;

    pthread_mutex_unlock(
        &g_lock
    );

    LOGI(
        "FunctionFS event thread stopped"
    );

    return NULL;
}

/*
 * --------------------------------------------------------------------------
 * Internal start / stop
 * --------------------------------------------------------------------------
 */

static int functionfs_start(
    const char *iso_path
)
{
    int ep0;

    struct stat file_info;

    if (
        iso_path == NULL ||
        iso_path[0] == '\0'
    ) {

        set_last_error(
            "ISO path is empty."
        );

        return -1;
    }

    if (
        stat(
            iso_path,
            &file_info
        ) != 0 ||
        !S_ISREG(
            file_info.st_mode
        )
    ) {

        set_last_error(
            "ISO path does not reference a readable regular file."
        );

        return -1;
    }

    if (
        access(
            iso_path,
            R_OK
        ) != 0
    ) {

        set_last_error(
            "ISO file is not readable by the app process."
        );

        return -1;
    }

    pthread_mutex_lock(
        &g_lock
    );

    if (g_running) {

        pthread_mutex_unlock(
            &g_lock
        );

        set_last_error(
            "FunctionFS is already running."
        );

        return -1;
    }

    pthread_mutex_unlock(
        &g_lock
    );

    if (
        prepare_functionfs_mount() != 0
    ) {
        return -1;
    }

    ep0 =
        open(
            EP0_PATH,
            O_RDWR
        );

    if (ep0 < 0) {

        set_last_errno(
            "FunctionFS ep0 could not be opened"
        );

        return -1;
    }

    if (
        write_full(
            ep0,
            &descriptors,
            sizeof(descriptors)
        ) !=
        (ssize_t)sizeof(descriptors)
    ) {

        set_last_errno(
            "FunctionFS descriptors could not be written"
        );

        close(
            ep0
        );

        return -1;
    }

    if (
        write_full(
            ep0,
            &strings,
            sizeof(strings)
        ) !=
        (ssize_t)sizeof(strings)
    ) {

        set_last_errno(
            "FunctionFS strings could not be written"
        );

        close(
            ep0
        );

        return -1;
    }

    pthread_mutex_lock(
        &g_lock
    );

    g_iso_path =
        strdup(
            iso_path
        );

    if (
        g_iso_path == NULL
    ) {

        pthread_mutex_unlock(
            &g_lock
        );

        close(
            ep0
        );

        set_last_error(
            "Out of memory."
        );

        return -1;
    }

    g_stop_requested = 0;
    g_running = 1;

    g_ep0 = ep0;

    if (
        pthread_create(
            &g_event_thread,
            NULL,
            event_thread_main,
            NULL
        ) != 0
    ) {

        free(
            g_iso_path
        );

        g_iso_path = NULL;

        g_running = 0;

        g_ep0 = -1;

        pthread_mutex_unlock(
            &g_lock
        );

        close(
            ep0
        );

        set_last_error(
            "pthread_create() failed for FunctionFS event thread."
        );

        return -1;
    }

    g_event_thread_created = 1;

    pthread_mutex_unlock(
        &g_lock
    );

    set_last_error(
        NULL
    );

    LOGI(
        "FunctionFS JNI backend started"
    );

    return 0;
}

static int functionfs_stop(void)
{
    pthread_t event_thread;
    pthread_t scsi_thread;

    int join_event = 0;
    int join_scsi = 0;

    int ep0 = -1;

    pthread_mutex_lock(
        &g_lock
    );

    g_stop_requested = 1;

    ep0 =
        g_ep0;

    if (
        g_event_thread_created
    ) {

        event_thread =
            g_event_thread;

        g_event_thread_created = 0;

        join_event = 1;
    }

    if (
        g_scsi_thread_created
    ) {

        scsi_thread =
            g_scsi_thread;

        g_scsi_thread_created = 0;

        join_scsi = 1;
    }

    pthread_mutex_unlock(
        &g_lock
    );

    close_data_endpoints();

    if (join_scsi) {

        (void)pthread_join(
            scsi_thread,
            NULL
        );
    }

    /*
     * The event loop uses poll() with a short timeout, so it observes
     * g_stop_requested without relying on cross-thread close() semantics.
     */
    if (join_event) {

        (void)pthread_join(
            event_thread,
            NULL
        );
    }

    if (ep0 >= 0) {
        close(ep0);
    }

    pthread_mutex_lock(
        &g_lock
    );

    g_ep0 = -1;

    if (
        g_iso_path != NULL
    ) {

        free(
            g_iso_path
        );

        g_iso_path = NULL;
    }

    g_running = 0;
    g_scsi_running = 0;

    pthread_mutex_unlock(
        &g_lock
    );

    set_last_error(
        NULL
    );

    LOGI(
        "FunctionFS JNI backend stopped"
    );

    return 0;
}

/*
 * --------------------------------------------------------------------------
 * JNI
 * --------------------------------------------------------------------------
 */

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_startNative(
    JNIEnv *env,
    jclass clazz,
    jstring isoPath
)
{
    const char *iso_path;
    int result;

    (void)clazz;

    if (isoPath == NULL) {
        return JNI_FALSE;
    }

    iso_path =
        (*env)->GetStringUTFChars(
            env,
            isoPath,
            NULL
        );

    if (iso_path == NULL) {
        return JNI_FALSE;
    }

    result =
        functionfs_start(
            iso_path
        );

    (*env)->ReleaseStringUTFChars(
        env,
        isoPath,
        iso_path
    );

    return result == 0
        ? JNI_TRUE
        : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_stopNative(
    JNIEnv *env,
    jclass clazz
)
{
    (void)env;
    (void)clazz;

    return functionfs_stop() == 0
        ? JNI_TRUE
        : JNI_FALSE;
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

    pthread_mutex_lock(
        &g_lock
    );

    running =
        g_running;

    pthread_mutex_unlock(
        &g_lock
    );

    return running
        ? JNI_TRUE
        : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_getLastErrorNative(
    JNIEnv *env,
    jclass clazz
)
{
    char error[sizeof(g_last_error)];

    (void)clazz;

    pthread_mutex_lock(
        &g_lock
    );

    snprintf(
        error,
        sizeof(error),
        "%s",
        g_last_error
    );

    pthread_mutex_unlock(
        &g_lock
    );

    return (*env)->NewStringUTF(
        env,
        error
    );
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_FunctionFileSystem_prepareNative(
    JNIEnv *env,
    jclass clazz
)
{
    (void)env;
    (void)clazz;

    return prepare_functionfs_mount() == 0
        ? JNI_TRUE
        : JNI_FALSE;
}

JNIEXPORT jint JNICALL
JNI_OnLoad(
    JavaVM *vm,
    void *reserved
)
{
    JNIEnv *env = NULL;

    (void)reserved;

    if (
        (*vm)->GetEnv(
            vm,
            (void **)&env,
            JNI_VERSION_1_6
        ) != JNI_OK
    ) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
JNI_OnUnload(
    JavaVM *vm,
    void *reserved
)
{
    (void)vm;
    (void)reserved;

    (void)functionfs_stop();
}
