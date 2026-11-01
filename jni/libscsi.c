/******************************************************************************
 * libscsi.c
 *
 * Copyright (c) 2026, Volkan Belek <vlkanblek@gmail.com>
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
#include <stdint.h>
#include <string.h>

struct CBW {
    uint32_t Signature;          
    uint32_t Tag;                
    uint32_t DataTransferLength;
    uint8_t  Flags;              
    uint8_t  LUN;                
    uint8_t  CBLength;           
    uint8_t  CDB[16];     
} __attribute__((packed));

#define CPU_TO_LE32(x) (x)

/*
 * SCSI CDB fields are big-endian. Write them byte by byte so the result does
 * not depend on the host byte order.
 */
static void put_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)((v >> 24) & 0xFF);
    p[1] = (uint8_t)((v >> 16) & 0xFF);
    p[2] = (uint8_t)((v >> 8) & 0xFF);
    p[3] = (uint8_t)(v & 0xFF);
}

static void put_be16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)((v >> 8) & 0xFF);
    p[1] = (uint8_t)(v & 0xFF);
}

JNIEXPORT jbyteArray JNICALL
Java_com_werismoln_multibooter_UsbScsiBridge_generateWrite10CBW(JNIEnv *env, jclass clazz, jint tag, jint lba, jshort sectorCount) {

    struct CBW cbw;
    uint16_t sectors = (uint16_t)sectorCount;   /* unsigned 16-bit block count */

    memset(&cbw, 0, sizeof(struct CBW));

    cbw.Signature = CPU_TO_LE32(0x43425355);
    cbw.Tag = CPU_TO_LE32((uint32_t)tag);
    cbw.DataTransferLength = CPU_TO_LE32((uint32_t)sectors * 512u);
    cbw.Flags = 0x00;
    cbw.LUN = 0;
    cbw.CBLength = 10;

    cbw.CDB[0] = 0x2A;                          /* WRITE(10) */
    put_be32(&cbw.CDB[2], (uint32_t)lba);
    put_be16(&cbw.CDB[7], sectors);

    jbyteArray result = (*env)->NewByteArray(env, sizeof(struct CBW));
    if (result == NULL) {
        return NULL;                            /* OutOfMemoryError is pending */
    }
    (*env)->SetByteArrayRegion(env, result, 0, sizeof(struct CBW), (const jbyte*)&cbw);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_werismoln_multibooter_UsbScsiBridge_parseReadCapacity(JNIEnv *env, jclass clazz, jbyteArray capacityData) {

    if (capacityData == NULL || (*env)->GetArrayLength(env, capacityData) < 4) {
        return -1;
    }

    jbyte *data = (*env)->GetByteArrayElements(env, capacityData, NULL);
    if (data == NULL) {
        return -1;
    }

    uint32_t last_lba = ((uint32_t)(data[0] & 0xFF) << 24) |
                        ((uint32_t)(data[1] & 0xFF) << 16) |
                        ((uint32_t)(data[2] & 0xFF) << 8)  |
                         (uint32_t)(data[3] & 0xFF);

    (*env)->ReleaseByteArrayElements(env, capacityData, data, JNI_ABORT);

    /* Widen before adding one so 0xFFFFFFFF does not wrap to zero. */
    return (jlong)last_lba + 1;
}
