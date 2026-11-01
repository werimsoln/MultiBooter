/******************************************************************************
 * libtftp.c
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
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>

#define TAG "LibTFTP_PXE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * Only characters that are safe inside a single-quoted "su -c '...'" shell
 * string are accepted. Anything else (quotes, spaces, ';', '&', '$', ...)
 * is rejected instead of being passed to a root shell.
 */
static int is_safe_shell_arg(const char *s) {
    if (s == NULL || s[0] == '\0') return 0;
    for (; *s; ++s) {
        char c = *s;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') ||
            c == '/' || c == '.' || c == '_' || c == '-' || c == '+' || c == ':') {
            continue;
        }
        return 0;
    }
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_TftpNative_startPxeServer(JNIEnv *env, jclass clazz, jstring binaryPath, jstring iface, jstring tftpRoot) {

    if (binaryPath == NULL || iface == NULL || tftpRoot == NULL) {
        return JNI_FALSE;
    }

    const char *c_bin = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    const char *c_iface = (*env)->GetStringUTFChars(env, iface, NULL);
    const char *c_root = (*env)->GetStringUTFChars(env, tftpRoot, NULL);
    jboolean ok = JNI_FALSE;

    if (c_bin == NULL || c_iface == NULL || c_root == NULL) {
        goto done;
    }

    if (!is_safe_shell_arg(c_bin) || !is_safe_shell_arg(c_iface) || !is_safe_shell_arg(c_root)) {
        LOGE("Refusing to start dnsmasq: unsafe characters in an argument.");
        goto done;
    }

    char cmd[2048];

    /*
     * Argument order matches the placeholders:
     *   ifconfig <iface> ...; <binary> --interface=<iface> ... --tftp-root=<root>
     * The command is started in the background, so system() only reports
     * whether the shell itself ran, not whether dnsmasq stayed up.
     */
    int n = snprintf(cmd, sizeof(cmd),
             "su -c 'pkill -9 dnsmasq ; "
             "ifconfig %s 192.168.2.1 netmask 255.255.255.0 up ; "
             "%s --interface=%s --dhcp-range=192.168.2.10,192.168.2.100,12h "
             "--enable-tftp --tftp-root=%s --dhcp-boot=pxelinux.0 &'",
             c_iface, c_bin, c_iface, c_root);

    if (n < 0 || (size_t)n >= sizeof(cmd)) {
        LOGE("PXE command is too long.");
        goto done;
    }

    LOGI("Starting PXE server through the native shell...");
    LOGI("Command: %s", cmd);

    int ret = system(cmd);

    if (ret != 0) {
        LOGE("Could not start dnsmasq! Return code: %d", ret);
    } else {
        ok = JNI_TRUE;
    }

done:
    if (c_bin) (*env)->ReleaseStringUTFChars(env, binaryPath, c_bin);
    if (c_iface) (*env)->ReleaseStringUTFChars(env, iface, c_iface);
    if (c_root) (*env)->ReleaseStringUTFChars(env, tftpRoot, c_root);

    return ok;
}

JNIEXPORT jboolean JNICALL
Java_com_werismoln_multibooter_TftpNative_stopPxeServer(JNIEnv *env, jclass clazz) {
    LOGI("Stopping PXE server (dnsmasq)...");

    int ret = system("su -c 'pkill -9 dnsmasq'");

    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}
