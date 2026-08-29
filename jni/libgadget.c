/******************************************************************************
 * libgadget.c
 *
 * Copyright (c) 2026, werismoln <vlkanblek@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/system_properties.h>

#define GADGET_UDC_PATH "/sys/kernel/config/usb_gadget/g1/UDC"
#define LUN_FILE_PATH   "/sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"
#define LUN_CDROM_PATH  "/sys/kernel/config/usb_gadget/g1/functions/mass_storage.0/lun.0/cdrom"
#define UDC_CLASS_DIR   "/sys/class/udc"

static int write_to_file(const char *path, const char *value) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    ssize_t len = strlen(value);
    ssize_t ret = write(fd, value, len);
    close(fd);
    return (ret == len) ? 0 : -1;
}

static int get_first_udc_name(char *buffer, size_t buf_size) {
    DIR *dir = opendir(UDC_CLASS_DIR);
    if (!dir) return -1;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] != '.') {
            strncpy(buffer, entry->d_name, buf_size - 1);
            buffer[buf_size - 1] = '\0';
            closedir(dir);
            return 0;
        }
    }
    closedir(dir);
    return -1;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_UsbGadget_enableMassStorageNative(
        JNIEnv *env, jclass clazz, jstring isoPath, jboolean asCdRom) {

    const char *iso_c_str = (*env)->GetStringUTFChars(env, isoPath, NULL);
    const char *cdrom_flag = asCdRom ? "1" : "0";
    char udc_name[128] = {0};

    __system_property_set("sys.usb.config", "none");

    write_to_file(GADGET_UDC_PATH, "\n");

    if (write_to_file(LUN_CDROM_PATH, cdrom_flag) != 0) {
        (*env)->ReleaseStringUTFChars(env, isoPath, iso_c_str);
        return JNI_FALSE;
    }

    if (write_to_file(LUN_FILE_PATH, iso_c_str) != 0) {
        (*env)->ReleaseStringUTFChars(env, isoPath, iso_c_str);
        return JNI_FALSE;
    }

    if (get_first_udc_name(udc_name, sizeof(udc_name)) != 0) {
        (*env)->ReleaseStringUTFChars(env, isoPath, iso_c_str);
        return JNI_FALSE;
    }

    int res = write_to_file(GADGET_UDC_PATH, udc_name);

    (*env)->ReleaseStringUTFChars(env, isoPath, iso_c_str);
    return (res == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_UsbGadget_disableMassStorageNative(
        JNIEnv *env, jclass clazz) {

    write_to_file(GADGET_UDC_PATH, "\n");
    write_to_file(LUN_FILE_PATH, "\n");
    __system_property_set("sys.usb.config", "mtp,adb");
    return JNI_TRUE;
}