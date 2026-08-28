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

JNIEXPORT jstring JNICALL

Java_com_werismoln_multibooter_NativeBridge_getNativeMessage(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, "NDK kütüphanesinden selamlar!");
}