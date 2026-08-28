/******************************************************************************
 * libscsi.c
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
#define CPU_TO_BE32(x) __builtin_bswap32(x)
#define CPU_TO_BE16(x) __builtin_bswap16(x)

JNIEXPORT jbyteArray JNICALL
Java_com_werismoln_multibooter_UsbScsiBridge_generateWrite10CBW(JNIEnv *env, jclass clazz, jint tag, jint lba, jshort sectorCount) {
    
    struct CBW cbw;
    memset(&cbw, 0, sizeof(struct CBW));

    cbw.Signature = CPU_TO_LE32(0x43425355);
    cbw.Tag = CPU_TO_LE32(tag);
    cbw.DataTransferLength = CPU_TO_LE32(sectorCount * 512);
    cbw.Flags = 0x00;
    cbw.LUN = 0;
    cbw.CBLength = 10;

    cbw.CDB[0] = 0x2A;
    cbw.CDB[2] = (CPU_TO_BE32(lba) >> 24) & 0xFF;
    cbw.CDB[3] = (CPU_TO_BE32(lba) >> 16) & 0xFF;
    cbw.CDB[4] = (CPU_TO_BE32(lba) >> 8) & 0xFF;
    cbw.CDB[5] = CPU_TO_BE32(lba) & 0xFF;
    cbw.CDB[7] = (CPU_TO_BE16(sectorCount) >> 8) & 0xFF;
    cbw.CDB[8] = CPU_TO_BE16(sectorCount) & 0xFF;

    jbyteArray result = (*env)->NewByteArray(env, sizeof(struct CBW));
    (*env)->SetByteArrayRegion(env, result, 0, sizeof(struct CBW), (const jbyte*)&cbw);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_werismoln_multibooter_UsbScsiBridge_parseReadCapacity(JNIEnv *env, jclass clazz, jbyteArray capacityData) {
    
    jbyte *data = (*env)->GetByteArrayElements(env, capacityData, NULL);
    
    uint32_t last_lba = ((uint32_t)(data[0] & 0xFF) << 24) |
                        ((uint32_t)(data[1] & 0xFF) << 16) |
                        ((uint32_t)(data[2] & 0xFF) << 8)  |
                         (uint32_t)(data[3] & 0xFF);

    

    (*env)->ReleaseByteArrayElements(env, capacityData, data, JNI_ABORT);

    return (jlong)(last_lba + 1);
}