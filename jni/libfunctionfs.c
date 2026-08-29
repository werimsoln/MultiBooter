#include <endian.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <linux/usb/functionfs.h>

#define FFS_DIR "/dev/usb-ffs/multiboot"
#define EP0_PATH FFS_DIR "/ep0"
#define EP1_PATH FFS_DIR "/ep1"
#define EP2_PATH FFS_DIR "/ep2"

#define USB_CLASS_MASS_STORAGE      0x08
#define SCSI_TRANSPARENT_SUBCLASS   0x06
#define BULK_ONLY_TRANSPORT_PROTOCOL 0x50

#define USB_DIR_IN  0x80
#define USB_DIR_OUT 0x00

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

#define CPU_TO_BE32(x) __builtin_bswap32(x)

struct {
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
        .magic = htole32(FUNCTIONFS_DESCRIPTORS_MAGIC_V2),
        .length = htole32(sizeof(descriptors)),
        .flags = htole32(FUNCTIONFS_HAS_FS_DESC | FUNCTIONFS_HAS_HS_DESC),
    },
    .fs_count = htole32(3),
    .hs_count = htole32(3),
    
    .fs_descs = {
        .intf = {
            .bLength = sizeof(descriptors.fs_descs.intf),
            .bDescriptorType = 4,
            .bNumEndpoints = 2,
            .bInterfaceClass = USB_CLASS_MASS_STORAGE,
            .bInterfaceSubClass = SCSI_TRANSPARENT_SUBCLASS,
            .bInterfaceProtocol = BULK_ONLY_TRANSPORT_PROTOCOL,
            .iInterface = 1,
        },
        .ep_in = {
            .bLength = sizeof(descriptors.fs_descs.ep_in),
            .bDescriptorType = 5,
            .bEndpointAddress = 1 | USB_DIR_IN,
            .bmAttributes = 2,
            .wMaxPacketSize = htole16(64),
        },
        .ep_out = {
            .bLength = sizeof(descriptors.fs_descs.ep_out),
            .bDescriptorType = 5,
            .bEndpointAddress = 2 | USB_DIR_OUT,
            .bmAttributes = 2,
            .wMaxPacketSize = htole16(64),
        },
    },
    
    .hs_descs = {
        .intf = {
            .bLength = sizeof(descriptors.hs_descs.intf),
            .bDescriptorType = 4,
            .bNumEndpoints = 2,
            .bInterfaceClass = USB_CLASS_MASS_STORAGE,
            .bInterfaceSubClass = SCSI_TRANSPARENT_SUBCLASS,
            .bInterfaceProtocol = BULK_ONLY_TRANSPORT_PROTOCOL,
            .iInterface = 1,
        },
        .ep_in = {
            .bLength = sizeof(descriptors.hs_descs.ep_in),
            .bDescriptorType = 5,
            .bEndpointAddress = 1 | USB_DIR_IN,
            .bmAttributes = 2,
            .wMaxPacketSize = htole16(512),
        },
        .ep_out = {
            .bLength = sizeof(descriptors.hs_descs.ep_out),
            .bDescriptorType = 5,
            .bEndpointAddress = 2 | USB_DIR_OUT,
            .bmAttributes = 2,
            .wMaxPacketSize = htole16(512),
        },
    },
};

#define STR_INTERFACE_ "MultiBooter Virtual CD-ROM"

struct {
    struct usb_functionfs_strings_head header;
    struct {
        __le16 code;
        const char str1[sizeof(STR_INTERFACE_)];
    } __attribute__((packed)) lang0;
} __attribute__((packed)) strings = {
    .header = {
        .magic = htole32(FUNCTIONFS_STRINGS_MAGIC),
        .length = htole32(sizeof(strings)),
        .str_count = htole32(1),
        .lang_count = htole32(1),
    },
    .lang0 = {
        .code = htole16(0x0409),
        .str1 = STR_INTERFACE_,
    },
};

struct ThreadArgs {
    const char* iso_path;
};

void* scsi_bot_loop(void* args) {
    struct ThreadArgs* t_args = (struct ThreadArgs*)args;
    
    int ep1 = open(EP1_PATH, O_RDWR);
    int ep2 = open(EP2_PATH, O_RDWR);
    if (ep1 < 0 || ep2 < 0) {
        printf("[Hata] EP1 veya EP2 acilamadi!\n");
        free(t_args);
        return NULL;
    }

    FILE *iso_file = fopen(t_args->iso_path, "rb");
    if (!iso_file) {
        printf("[Hata] ISO dosyasi okunamadi: %s\n", t_args->iso_path);
        close(ep1); close(ep2);
        free(t_args);
        return NULL;
    }

    fseek(iso_file, 0, SEEK_END);
    uint64_t iso_size = ftell(iso_file);
    uint32_t total_sectors = iso_size / 512;
    
    struct CBW cbw;
    struct CSW csw;
    csw.Signature = htole32(0x53425355);
    
    printf("[SCSI] Veri dongusu basladi. PC'den komut bekleniyor...\n");

    while (1) {
        int ret = read(ep2, &cbw, sizeof(cbw));
        if (ret < 0) break;

        csw.Tag = cbw.Tag;
        csw.DataResidue = cbw.DataTransferLength;
        csw.Status = 0;

        uint8_t opcode = cbw.CDB[0];

        switch (opcode) {
            case 0x12: {
                uint8_t inquiry_data[36] = {
                    0x05, 0x80, 0x02, 0x02, 31, 0, 0, 0,
                    'M','u','l','t','i','B','o','o','t', 
                    'V','i','r','t','u','a','l',' ','C','D', 
                    ' ',' ',' ',' ',' ',' ',' ',' ',
                    '1','.','0','0' 
                };
                write(ep1, inquiry_data, sizeof(inquiry_data));
                csw.DataResidue = 0;
                break;
            }
            case 0x25: {
                uint32_t last_lba = CPU_TO_BE32(total_sectors - 1);
                uint32_t block_size = CPU_TO_BE32(512);
                uint8_t cap_data[8];
                memcpy(cap_data, &last_lba, 4);
                memcpy(cap_data + 4, &block_size, 4);
                write(ep1, cap_data, 8);
                csw.DataResidue = 0;
                break;
            }
            case 0x28: {
                uint32_t start_lba = (cbw.CDB[2] << 24) | (cbw.CDB[3] << 16) | (cbw.CDB[4] << 8) | cbw.CDB[5];
                uint16_t blocks = (cbw.CDB[7] << 8) | cbw.CDB[8];
                
                uint32_t read_bytes = blocks * 512;
                uint8_t* buffer = malloc(read_bytes);
                
                fseek(iso_file, (uint64_t)start_lba * 512, SEEK_SET);
                fread(buffer, 1, read_bytes, iso_file);
                
                write(ep1, buffer, read_bytes);
                free(buffer);
                
                csw.DataResidue = 0;
                break;
            }
            case 0x00:
            case 0x1E:
                csw.DataResidue = 0;
                break;
            default:
                csw.Status = 1;
                break;
        }

        write(ep1, &csw, sizeof(csw));
    }

    fclose(iso_file);
    close(ep1);
    close(ep2);
    free(t_args);
    return NULL;
}


// --- ANA FONKSIYON ---
int main(int argc, char *argv[]) {
    if (argc < 2) {
        printf("[Hata] Kullanim: ffs_gadget <ISO_YOLU>\n");
        return 1;
    }

    const char *iso_path = argv[1];
    printf("[FFS] Baslatiliyor... Hedef ISO: %s\n", iso_path);

    int ep0 = open(EP0_PATH, O_RDWR);
    if (ep0 < 0) {
        perror("[Hata] ep0 acilamadi! Root veya mount komutlarini kontrol et");
        return 1;
    }

    if (write(ep0, &descriptors, sizeof(descriptors)) < 0) {
        perror("[Hata] Descriptors yazilamadi");
        close(ep0);
        return 1;
    }

    if (write(ep0, &strings, sizeof(strings)) < 0) {
        perror("[Hata] Strings yazilamadi");
        close(ep0);
        return 1;
    }

    struct usb_functionfs_event event;
    while (1) {
        int ret = read(ep0, &event, sizeof(event));
        if (ret < 0) {
            perror("[FFS] Olay okuma hatasi");
            break;
        }

        switch (event.type) {
            case FUNCTIONFS_BIND:
                printf("[FFS Olay] BIND: Bilgisayar cihazi gordu.\n");
                break;
            case FUNCTIONFS_ENABLE:
                printf("[FFS Olay] ENABLE: Baglanti aktiflesti! (EP1 ve EP2 kullanima hazir)\n");
                
                pthread_t scsi_thread;
                struct ThreadArgs *args = malloc(sizeof(struct ThreadArgs));
                args->iso_path = iso_path;
                pthread_create(&scsi_thread, NULL, scsi_bot_loop, args);
                break;
            case FUNCTIONFS_DISABLE:
                printf("[FFS Olay] DISABLE: Baglanti duraklatildi.\n");
                break;
            case FUNCTIONFS_SETUP:
                printf("[FFS Olay] SETUP: Ozel komut alindi.\n");
                break;
            default:
                printf("[FFS Olay] Diger: %d\n", event.type);
                break;
        }
    }

    close(ep0);
    return 0;
}