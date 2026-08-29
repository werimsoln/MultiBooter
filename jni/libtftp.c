/******************************************************************************
 * libtftp.c
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
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>

#define TAG "LibTFTP_PXE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_TftpNative_startPxeServer(JNIEnv *env, jclass clazz, jstring binaryPath, jstring iface, jstring tftpRoot) {
    
    const char *c_bin = (*env)->GetStringUTFChars(env, binaryPath, 0);
    const char *c_iface = (*env)->GetStringUTFChars(env, iface, 0);
    const char *c_root = (*env)->GetStringUTFChars(env, tftpRoot, 0);

    char cmd[2048];
    
    snprintf(cmd, sizeof(cmd),
             "su -c 'pkill -9 dnsmasq ; "
             "ifconfig %s 192.168.2.1 netmask 255.255.255.0 up ; "
             "%s --interface=%s --dhcp-range=192.168.2.10,192.168.2.100,12h "
             "--enable-tftp --tftp-root=%s --dhcp-boot=pxelinux.0 &'",
             c_iface, c_iface, c_bin, c_iface, c_root);

    LOGI("PXE Sunucusu native shell uzerinden baslatiliyor...");
    LOGI("Komut: %s", cmd);

    int ret = system(cmd);

    if (ret != 0) {
        LOGE("dnsmasq baslatilamadi! Hata Kodu: %d", ret);
    }

    (*env)->ReleaseStringUTFChars(env, binaryPath, c_bin);
    (*env)->ReleaseStringUTFChars(env, iface, c_iface);
    (*env)->ReleaseStringUTFChars(env, tftpRoot, c_root);

    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_TftpNative_stopPxeServer(JNIEnv *env, jclass clazz) {
    LOGI("PXE Sunucusu (dnsmasq) durduruluyor...");
    
    int ret = system("su -c 'pkill -9 dnsmasq'");
    
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}