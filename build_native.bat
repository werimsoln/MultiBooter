@echo off
setlocal EnableExtensions EnableDelayedExpansion

echo ============================================================
echo              MultiBooter NATIVE BUILD
echo ============================================================
echo.

REM ============================================================
REM CONFIGURATION
REM ============================================================
REM Java/Android target SDK ile native minimum API ayni sey degildir.
REM MultiBooter minSdk 26 oldugu icin native kodu android26'ya target
REM ediyoruz. Bu, arm64-v8a native dosyalarinin Android 8.0+ ile
REM uyumlu kalmasini saglar.
REM ============================================================

set "ANDROID_HOME=C:\Android\sdk"
set "NDK_VERSION=26.1.10909125"
set "ANDROID_MIN_API=26"
set "ABI=arm64-v8a"

set "NDK_ROOT=%ANDROID_HOME%\ndk\%NDK_VERSION%"
set "NDK_BIN=%NDK_ROOT%\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "CLANG=%NDK_BIN%\clang.exe"
set "STRIP=%NDK_BIN%\llvm-strip.exe"

set "JNI_DIR=jni"
set "LIB_DIR=lib\%ABI%"
set "DNSMASQ_SRC=src\native\dnsmasq\src"
set "ASSET_DIR=src\main\assets"
set "DNSMASQ_OUT=%ASSET_DIR%\dnsmasq"
set "DNSMASQ_RSP=dnsmasq_sources.rsp"

REM ============================================================
REM ENVIRONMENT CHECK
REM ============================================================

echo [0/8] Ortam kontrol ediliyor...

if not exist "%NDK_ROOT%" (
    echo [ERROR] Android NDK bulunamadi:
    echo         %NDK_ROOT%
    exit /b 1
)

if not exist "%CLANG%" (
    echo [ERROR] clang.exe bulunamadi:
    echo         %CLANG%
    exit /b 1
)

if not exist "%STRIP%" (
    echo [ERROR] llvm-strip.exe bulunamadi:
    echo         %STRIP%
    exit /b 1
)

if not exist "%JNI_DIR%" (
    echo [ERROR] jni klasoru bulunamadi.
    exit /b 1
)

if not exist "%DNSMASQ_SRC%\dnsmasq.c" (
    echo [ERROR] dnsmasq kaynaklari bulunamadi:
    echo         %DNSMASQ_SRC%
    exit /b 1
)

echo [OK] NDK:
echo      %NDK_ROOT%
echo [OK] ABI: %ABI%
echo [OK] Native minimum API: %ANDROID_MIN_API%
echo.

REM ============================================================
REM REQUIRED SOURCE CHECK
REM ============================================================

echo [1/8] Native kaynaklar kontrol ediliyor...

for %%F in (
    libgadget.c
    libscsi.c
    libtftp.c
    libexfat.c
    libfunctionfs.c
) do (
    if not exist "%JNI_DIR%\%%F" (
        echo [ERROR] Eksik native kaynak:
        echo         %JNI_DIR%\%%F
        exit /b 1
    )
)

echo [OK] Tum native kaynaklar mevcut.
echo.

REM ============================================================
REM OUTPUT DIRECTORIES / CLEAN
REM ============================================================

echo [2/8] Native ciktilar temizleniyor...

if not exist "lib" mkdir "lib"
if not exist "%LIB_DIR%" mkdir "%LIB_DIR%"
if not exist "%ASSET_DIR%" mkdir "%ASSET_DIR%"

if exist "%LIB_DIR%\libgadget.so" del /f /q "%LIB_DIR%\libgadget.so"
if exist "%LIB_DIR%\libscsi.so" del /f /q "%LIB_DIR%\libscsi.so"
if exist "%LIB_DIR%\libtftp.so" del /f /q "%LIB_DIR%\libtftp.so"
if exist "%LIB_DIR%\libexfat.so" del /f /q "%LIB_DIR%\libexfat.so"
if exist "%LIB_DIR%\libfunctionfs.so" del /f /q "%LIB_DIR%\libfunctionfs.so"

if exist "%DNSMASQ_OUT%" del /f /q "%DNSMASQ_OUT%"
if exist "%DNSMASQ_RSP%" del /f /q "%DNSMASQ_RSP%"

REM Eski standalone FunctionFS mimarisinden kalan dosyayi paketlemeyelim.
if exist "%ASSET_DIR%\ffs_gadget" (
    echo [INFO] Eski assets\ffs_gadget siliniyor...
    del /f /q "%ASSET_DIR%\ffs_gadget"
)

echo [OK] Temizlik tamam.
echo.

REM ============================================================
REM COMMON NATIVE FLAGS
REM ============================================================

set "TARGET=--target=aarch64-linux-android%ANDROID_MIN_API%"
set "SO_CFLAGS=-shared -fPIC -O2 -ffunction-sections -fdata-sections -Wall -Wextra"
set "SO_LDFLAGS=-Wl,--gc-sections"

REM ============================================================
REM libgadget.so
REM ============================================================

echo [3/8] libgadget.so derleniyor...

"%CLANG%" ^
    %TARGET% ^
    %SO_CFLAGS% ^
    "%JNI_DIR%\libgadget.c" ^
    %SO_LDFLAGS% ^
    -o "%LIB_DIR%\libgadget.so"

if errorlevel 1 (
    echo.
    echo [ERROR] libgadget.so derlenemedi.
    exit /b 1
)

echo [OK] %LIB_DIR%\libgadget.so
echo.

REM ============================================================
REM libscsi.so
REM ============================================================

echo [4/8] libscsi.so derleniyor...

"%CLANG%" ^
    %TARGET% ^
    %SO_CFLAGS% ^
    "%JNI_DIR%\libscsi.c" ^
    %SO_LDFLAGS% ^
    -o "%LIB_DIR%\libscsi.so"

if errorlevel 1 (
    echo.
    echo [ERROR] libscsi.so derlenemedi.
    exit /b 1
)

echo [OK] %LIB_DIR%\libscsi.so
echo.

REM ============================================================
REM libtftp.so
REM ============================================================

echo [5/8] libtftp.so derleniyor...

"%CLANG%" ^
    %TARGET% ^
    %SO_CFLAGS% ^
    "%JNI_DIR%\libtftp.c" ^
    %SO_LDFLAGS% ^
    -llog ^
    -o "%LIB_DIR%\libtftp.so"

if errorlevel 1 (
    echo.
    echo [ERROR] libtftp.so derlenemedi.
    exit /b 1
)

echo [OK] %LIB_DIR%\libtftp.so
echo.

REM ============================================================
REM libexfat.so
REM ============================================================

echo [6/8] libexfat.so derleniyor...

"%CLANG%" ^
    %TARGET% ^
    %SO_CFLAGS% ^
    "%JNI_DIR%\libexfat.c" ^
    %SO_LDFLAGS% ^
    -o "%LIB_DIR%\libexfat.so"

if errorlevel 1 (
    echo.
    echo [ERROR] libexfat.so derlenemedi.
    exit /b 1
)

echo [OK] %LIB_DIR%\libexfat.so
echo.

REM ============================================================
REM libfunctionfs.so
REM ============================================================

echo [7/8] libfunctionfs.so derleniyor...

"%CLANG%" ^
    %TARGET% ^
    %SO_CFLAGS% ^
    "%JNI_DIR%\libfunctionfs.c" ^
    %SO_LDFLAGS% ^
    -pthread ^
    -llog ^
    -o "%LIB_DIR%\libfunctionfs.so"

if errorlevel 1 (
    echo.
    echo [ERROR] libfunctionfs.so derlenemedi.
    exit /b 1
)

echo [OK] %LIB_DIR%\libfunctionfs.so
echo.

REM ============================================================
REM DNSMASQ
REM ============================================================

echo [8/8] dnsmasq derleniyor...

REM cmd.exe wildcard genisletmesini compiler'a birakmak yerine
REM tum .c dosyalarini response-file icine yaziyoruz.
REM NOT: Clang ters slash'lari yuttugu icin yollari duz slash (/) ile degistiriyoruz!
> "%DNSMASQ_RSP%" (
    for %%F in ("%DNSMASQ_SRC%"\*.c) do (
        set "FILE_PATH=%%~fF"
        echo "!FILE_PATH:\=/!"
    )
)

if not exist "%DNSMASQ_RSP%" (
    echo [ERROR] dnsmasq source response file olusturulamadi.
    exit /b 1
)

for %%A in ("%DNSMASQ_RSP%") do (
    if %%~zA EQU 0 (
        echo [ERROR] dnsmasq icin .c kaynaklari bulunamadi.
        del /f /q "%DNSMASQ_RSP%" >nul 2>&1
        exit /b 1
    )
)

"%CLANG%" ^
    %TARGET% ^
    -O2 ^
    -fPIE ^
    -pie ^
    -ffunction-sections ^
    -fdata-sections ^
    -Wl,--gc-sections ^
    -DNO_IPV6 ^
    -DNO_DBUS ^
    -DVERSION=\"2.89\" ^
    -DETHER_ADDR_LEN=6 ^
    -Wno-macro-redefined ^
    @"%DNSMASQ_RSP%" ^
    -llog ^
    -o "%DNSMASQ_OUT%"

if errorlevel 1 (
    echo.
    echo [ERROR] dnsmasq derlenemedi.
    del /f /q "%DNSMASQ_RSP%" >nul 2>&1
    exit /b 1
)

del /f /q "%DNSMASQ_RSP%" >nul 2>&1

echo [OK] %DNSMASQ_OUT%
echo.

REM ============================================================
REM STRIP
REM ============================================================

echo [INFO] Native dosyalar strip ediliyor...

for %%F in (
    "%LIB_DIR%\libgadget.so"
    "%LIB_DIR%\libscsi.so"
    "%LIB_DIR%\libtftp.so"
    "%LIB_DIR%\libexfat.so"
    "%LIB_DIR%\libfunctionfs.so"
    "%DNSMASQ_OUT%"
) do (
    if exist "%%~F" (
        "%STRIP%" --strip-unneeded "%%~F"
        if errorlevel 1 (
            echo [WARNING] Strip basarisiz: %%~F
        )
    )
)

echo.
echo ============================================================
echo              NATIVE BUILD BASARILI
echo ============================================================
echo.

echo Native shared libraries:
for %%F in (
    "%LIB_DIR%\libgadget.so"
    "%LIB_DIR%\libscsi.so"
    "%LIB_DIR%\libtftp.so"
    "%LIB_DIR%\libexfat.so"
    "%LIB_DIR%\libfunctionfs.so"
) do (
    if exist "%%~F" (
        for %%S in ("%%~F") do (
            echo   %%~nxF - %%~zS bytes
        )
    )
)

echo.
echo Native asset:
if exist "%DNSMASQ_OUT%" (
    for %%S in ("%DNSMASQ_OUT%") do (
        echo   dnsmasq - %%~zS bytes
    )
)

echo.
echo Output:
echo   %CD%\%LIB_DIR%
echo   %CD%\%DNSMASQ_OUT%
echo.
echo ============================================================

endlocal
exit /b 0