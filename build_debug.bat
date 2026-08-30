@echo off
setlocal EnableExtensions EnableDelayedExpansion

echo ==========================================
echo        MultiBooter DEBUG BUILD
echo ==========================================
echo.

REM ============================================================
REM CONFIGURATION
REM ============================================================

set "ANDROID_HOME=C:\Android\sdk"
set "ANDROID_API=34"
set "NDK_VERSION=26.1.10909125"
set "BUILD_TOOLS_VERSION=34.0.0"

set "PLATFORM=%ANDROID_HOME%\platforms\android-%ANDROID_API%\android.jar"
set "BUILD_TOOLS=%ANDROID_HOME%\build-tools\%BUILD_TOOLS_VERSION%"
set "NDK_BIN=%ANDROID_HOME%\ndk\%NDK_VERSION%\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "CLANG=%NDK_BIN%\clang.exe"

set "AAPT2=%BUILD_TOOLS%\aapt2.exe"
set "AAPT=%BUILD_TOOLS%\aapt.exe"
set "ZIPALIGN=%BUILD_TOOLS%\zipalign.exe"
set "APKSIGNER=%BUILD_TOOLS%\apksigner.bat"
set "D8=%BUILD_TOOLS%\d8.bat"

REM ============================================================
REM ENVIRONMENT CHECK
REM ============================================================

echo [0/12] Ortam kontrol ediliyor...

if not exist "%PLATFORM%" ( echo [ERROR] android.jar bulunamadi & exit /b 1 )
if not exist "%CLANG%" ( echo [ERROR] NDK clang bulunamadi & exit /b 1 )
if not exist "%AAPT2%" ( echo [ERROR] aapt2.exe bulunamadi & exit /b 1 )
if not exist "%AAPT%" ( echo [ERROR] aapt.exe bulunamadi & exit /b 1 )
if not exist "%ZIPALIGN%" ( echo [ERROR] zipalign.exe bulunamadi & exit /b 1 )
if not exist "%APKSIGNER%" ( echo [ERROR] apksigner.bat bulunamadi & exit /b 1 )
if not exist "%D8%" ( echo [ERROR] d8.bat bulunamadi & exit /b 1 )

where javac >nul 2>&1
if errorlevel 1 ( echo [ERROR] javac bulunamadi & exit /b 1 )

where jar >nul 2>&1
if errorlevel 1 ( echo [ERROR] jar araci bulunamadi & exit /b 1 )

where keytool >nul 2>&1
if errorlevel 1 ( echo [ERROR] keytool bulunamadi & exit /b 1 )

echo [OK] Ortam hazir.
echo.

REM ============================================================
REM CLEAN
REM ============================================================

echo [1/12] Eski build temizleniyor...

if exist gen rmdir /s /q gen
if exist obj rmdir /s /q obj
if exist dex-out rmdir /s /q dex-out
if exist lib rmdir /s /q lib

if exist compiled_res.zip del /f /q compiled_res.zip
if exist sources.txt del /f /q sources.txt
if exist classes.jar del /f /q classes.jar
if exist classes.dex del /f /q classes.dex
if exist app-*.apk del /f /q app-*.apk

mkdir gen
mkdir obj
mkdir dex-out
mkdir lib\arm64-v8a
mkdir lib\armeabi-v7a
mkdir lib\x86
mkdir lib\x86_64

if not exist src\main\assets mkdir src\main\assets
if exist src\main\assets\ffs_gadget del /f /q src\main\assets\ffs_gadget
if exist src\main\assets\dnsmasq del /f /q src\main\assets\dnsmasq


echo [OK] Temizlik tamam.
echo.

REM ============================================================
REM NATIVE BUILD - MULTI ABI
REM ============================================================

echo [2/12] Native kodlar 4 ABI icin derleniyor...

for %%F in (libgadget.c libscsi.c libtftp.c libexfat.c libfunctionfs.c) do (
    if not exist "jni\%%F" (
        echo [ERROR] jni\%%F bulunamadi.
        exit /b 1
    )
)

for %%A in (arm64-v8a armeabi-v7a x86 x86_64) do (

    set "TARGET="

    if "%%A"=="arm64-v8a"   set "TARGET=aarch64-linux-android%ANDROID_API%"
    if "%%A"=="armeabi-v7a" set "TARGET=armv7a-linux-androideabi%ANDROID_API%"
    if "%%A"=="x86"          set "TARGET=i686-linux-android%ANDROID_API%"
    if "%%A"=="x86_64"       set "TARGET=x86_64-linux-android%ANDROID_API%"

    if not defined TARGET (
        echo [ERROR] Bilinmeyen ABI: %%A
        exit /b 1
    )

    echo.
    echo [ABI %%A] Target: !TARGET!

    echo [%%A 1/5] libgadget.so
    "%CLANG%" --target=!TARGET! -shared -fPIC -O2 -Wall -Wextra "jni\libgadget.c" -o "lib\%%A\libgadget.so"
    if errorlevel 1 exit /b 1

    echo [%%A 2/5] libscsi.so
    "%CLANG%" --target=!TARGET! -shared -fPIC -O2 -Wall -Wextra "jni\libscsi.c" -o "lib\%%A\libscsi.so"
    if errorlevel 1 exit /b 1

    echo [%%A 3/5] libtftp.so
    "%CLANG%" --target=!TARGET! -shared -fPIC -O2 -Wall -Wextra "jni\libtftp.c" -llog -o "lib\%%A\libtftp.so"
    if errorlevel 1 exit /b 1

    echo [%%A 4/5] libexfat.so
    "%CLANG%" --target=!TARGET! -shared -fPIC -O2 -Wall -Wextra "jni\libexfat.c" -llog -o "lib\%%A\libexfat.so"
    if errorlevel 1 exit /b 1

    echo [%%A 5/5] libfunctionfs.so
    "%CLANG%" --target=!TARGET! -shared -fPIC -O2 -Wall -Wextra "jni\libfunctionfs.c" -pthread -llog -o "lib\%%A\libfunctionfs.so"
    if errorlevel 1 exit /b 1
)

echo.
echo [OK] Native kutuphaneler derlendi:
echo      arm64-v8a
echo      armeabi-v7a
echo      x86
echo      x86_64
echo.

REM ============================================================
REM DNSMASQ - MULTI ABI
REM ============================================================

echo [3/12] dnsmasq 4 ABI icin derleniyor...

set "DNSMASQ_SRC=src\native\dnsmasq\src"
set "DNSMASQ_ASSETS=src\main\assets"

if exist "%DNSMASQ_SRC%\dnsmasq.c" (

    REM Tum dnsmasq C kaynaklarini tek response file icine yaz.
    REM Windows path ayiraclarini clang icin / karakterine ceviriyoruz.
    > "dnsmasq_sources.rsp" (
        for %%F in ("%DNSMASQ_SRC%"\*.c) do (
            set "FILE_PATH=%%~fF"
            echo !FILE_PATH:\=/!
        )
    )

    for %%A in (arm64-v8a armeabi-v7a x86 x86_64) do (

        set "DNSMASQ_TARGET="
        set "DNSMASQ_OUTPUT="

        if "%%A"=="arm64-v8a" (
            set "DNSMASQ_TARGET=aarch64-linux-android%ANDROID_API%"
            set "DNSMASQ_OUTPUT=%DNSMASQ_ASSETS%\dnsmasq-arm64-v8a"
        )

        if "%%A"=="armeabi-v7a" (
            set "DNSMASQ_TARGET=armv7a-linux-androideabi%ANDROID_API%"
            set "DNSMASQ_OUTPUT=%DNSMASQ_ASSETS%\dnsmasq-armeabi-v7a"
        )

        if "%%A"=="x86" (
            set "DNSMASQ_TARGET=i686-linux-android%ANDROID_API%"
            set "DNSMASQ_OUTPUT=%DNSMASQ_ASSETS%\dnsmasq-x86"
        )

        if "%%A"=="x86_64" (
            set "DNSMASQ_TARGET=x86_64-linux-android%ANDROID_API%"
            set "DNSMASQ_OUTPUT=%DNSMASQ_ASSETS%\dnsmasq-x86_64"
        )

        if not defined DNSMASQ_TARGET (
            echo [ERROR] Bilinmeyen dnsmasq ABI: %%A
            del /f /q "dnsmasq_sources.rsp" >nul 2>&1
            exit /b 1
        )

        echo.
        echo [dnsmasq %%A] Target: !DNSMASQ_TARGET!

        "%CLANG%" ^
            --target=!DNSMASQ_TARGET! ^
            -O2 ^
            -fPIE ^
            -pie ^
            -DNO_IPV6 ^
            -DNO_DBUS ^
            -DVERSION=\"2.89\" ^
            -DETHER_ADDR_LEN=6 ^
            -Wno-macro-redefined ^
            @"dnsmasq_sources.rsp" ^
            -llog ^
            -o "!DNSMASQ_OUTPUT!"

        if errorlevel 1 (
            echo [ERROR] dnsmasq %%A icin derlenemedi.
            del /f /q "dnsmasq_sources.rsp" >nul 2>&1
            exit /b 1
        )

        if not exist "!DNSMASQ_OUTPUT!" (
            echo [ERROR] dnsmasq %%A cikti dosyasi olusmadi.
            del /f /q "dnsmasq_sources.rsp" >nul 2>&1
            exit /b 1
        )

        echo [OK] %%A dnsmasq hazir.
    )

    del /f /q "dnsmasq_sources.rsp" >nul 2>&1

) else (

    echo [INFO] dnsmasq kaynaklari bulunamadi; mevcut 4 ABI asset kontrol ediliyor.

    for %%A in (arm64-v8a armeabi-v7a x86 x86_64) do (
        if not exist "%DNSMASQ_ASSETS%\dnsmasq-%%A" (
            echo [ERROR] %DNSMASQ_ASSETS%\dnsmasq-%%A bulunamadi.
            exit /b 1
        )
    )
)

echo.
echo [OK] dnsmasq 4 ABI icin hazir:
echo      dnsmasq-arm64-v8a
echo      dnsmasq-armeabi-v7a
echo      dnsmasq-x86
echo      dnsmasq-x86_64
echo.

REM ============================================================
REM RESOURCE COMPILE
REM ============================================================

echo [4/12] Resources derleniyor...
"%AAPT2%" compile --dir res -o compiled_res.zip
if errorlevel 1 exit /b 1
echo [OK] Resources compile edildi.
echo.

REM ============================================================
REM RESOURCE LINK + ASSETS
REM ============================================================

echo [5/12] Resources ve assets link ediliyor...
"%AAPT2%" link -o app-unaligned.apk -I "%PLATFORM%" --manifest AndroidManifest.xml -R compiled_res.zip -A src\main\assets --auto-add-overlay --java gen
if errorlevel 1 exit /b 1
echo [OK] Resources ve assets eklendi.
echo.

REM ============================================================
REM JAVA
REM ============================================================

echo [6/12] Java kaynaklari derleniyor...
(
    for /r src %%F in (*.java) do echo %%F
    for /r gen %%F in (*.java) do echo %%F
) > sources.txt

javac --release 8 -encoding UTF-8 -d obj -cp "%PLATFORM%" @sources.txt
if errorlevel 1 exit /b 1
echo [OK] Java derlendi.
echo.

REM ============================================================
REM D8 (DEX)
REM ============================================================

echo [7/12] D8 ile classes.dex olusturuluyor...

REM obj klasorunu dogrudan D8'e vermek yerine (Unsupported source file hatasini asmak icin)
REM once obj icindeki class'lari bir jar dosyasina ceviriyoruz.
jar cf classes.jar -C obj .
if errorlevel 1 exit /b 1

call "%D8%" --debug --min-api 26 --lib "%PLATFORM%" --output dex-out classes.jar
if errorlevel 1 exit /b 1

echo [OK] classes.dex hazir.
echo.

REM ============================================================
REM PACKAGE DEX + NATIVE LIBS
REM ============================================================

echo [8/12] DEX ve native kutuphaneler APK'ya ekleniyor...

copy /y dex-out\classes.dex classes.dex >nul

REM Windows'un slash (\) ayirici problemlerini ve PowerShell zayifliklarini
REM tamamen asmak icin Java'nin yerleşik "jar" aracini kullaniyoruz.
REM "jar uf" komutu otomatik olarak APK'nin icine dogru klasor hiyerarsisi
REM ve duz slash (/) ile dosyalari enjekte eder.
jar uf app-unaligned.apk classes.dex lib
if errorlevel 1 exit /b 1

del /f /q classes.dex
del /f /q classes.jar
del /f /s /q dex-out >nul 2>&1

echo [OK] DEX ve native kutuphaneler eklendi.
echo.

REM ============================================================
REM APK CONTENT VERIFY
REM ============================================================

echo [9/12] APK icerigi kontrol ediliyor...

"%AAPT%" list app-unaligned.apk | findstr /x "classes.dex" >nul
if errorlevel 1 ( echo [ERROR] classes.dex yok. & exit /b 1 )

for %%A in (arm64-v8a armeabi-v7a x86 x86_64) do (
    for %%L in (libgadget.so libscsi.so libtftp.so libexfat.so libfunctionfs.so) do (
        "%AAPT%" list app-unaligned.apk | findstr /x "lib/%%A/%%L" >nul
        if errorlevel 1 (
            echo [ERROR] lib/%%A/%%L APK icinde yok.
            exit /b 1
        )
    )
)


for %%A in (arm64-v8a armeabi-v7a x86 x86_64) do (
    "%AAPT%" list app-unaligned.apk | findstr /x "assets/dnsmasq-%%A" >nul
    if errorlevel 1 (
        echo [ERROR] assets/dnsmasq-%%A APK icinde yok.
        exit /b 1
    )
)

echo [OK] APK icerigi dogru.
echo.

REM ============================================================
REM ZIPALIGN
REM ============================================================

echo [10/12] APK align ediliyor...
"%ZIPALIGN%" -f -p 4 app-unaligned.apk app-aligned.apk
if errorlevel 1 exit /b 1
echo [OK] APK align edildi.
echo.

REM ============================================================
REM SIGN
REM ============================================================

echo [11/12] Debug APK imzalaniyor...

if not exist debug.keystore (
    keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Test, OU=Test, O=Test, L=Test, S=Test, C=TR"
)

call "%APKSIGNER%" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out app-debug.apk app-aligned.apk
if errorlevel 1 exit /b 1

echo [OK] APK imzalandi.
echo.

REM ============================================================
REM VERIFY
REM ============================================================

echo [12/12] APK dogrulaniyor...
call "%APKSIGNER%" verify --verbose app-debug.apk
if errorlevel 1 exit /b 1

echo.
echo ==========================================
echo        DEBUG BUILD BASARILI
echo ==========================================
echo.
echo APK: %CD%\app-debug.apk
echo.
endlocal
exit /b 0