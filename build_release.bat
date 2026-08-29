@echo off
setlocal EnableExtensions EnableDelayedExpansion

echo ==========================================
echo       MultiBooter RELEASE BUILD
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

set "R8_JAR=%ANDROID_HOME%\r8\r8.jar"
set "PROGUARD=%~dp0proguard-rules.pro"

REM Release signing defaults.
REM Gercek yayin anahtarini kullanmak icin bunlari degistir.
set "RELEASE_KEYSTORE=release.keystore"
set "RELEASE_KEY_ALIAS=multibooter"

REM ============================================================
REM ENVIRONMENT CHECK
REM ============================================================

echo [0/12] Ortam kontrol ediliyor...

if not exist "%PLATFORM%" (
    echo [ERROR] android.jar bulunamadi:
    echo %PLATFORM%
    exit /b 1
)

if not exist "%CLANG%" (
    echo [ERROR] NDK clang bulunamadi:
    echo %CLANG%
    exit /b 1
)

if not exist "%AAPT2%" (
    echo [ERROR] aapt2.exe bulunamadi.
    exit /b 1
)

if not exist "%AAPT%" (
    echo [ERROR] aapt.exe bulunamadi.
    exit /b 1
)

if not exist "%ZIPALIGN%" (
    echo [ERROR] zipalign.exe bulunamadi.
    exit /b 1
)

if not exist "%APKSIGNER%" (
    echo [ERROR] apksigner.bat bulunamadi.
    exit /b 1
)

if not exist "%PROGUARD%" (
    echo [ERROR] proguard-rules.pro bulunamadi.
    exit /b 1
)

where javac >nul 2>&1
if errorlevel 1 (
    echo [ERROR] javac bulunamadi.
    exit /b 1
)

where jar >nul 2>&1
if errorlevel 1 (
    echo [ERROR] jar.exe bulunamadi.
    exit /b 1
)

REM R8 locate
set "R8_BAT="

if not exist "%R8_JAR%" (

    if exist "%BUILD_TOOLS%\lib\r8.jar" (
        set "R8_JAR=%BUILD_TOOLS%\lib\r8.jar"
    ) else if exist "%BUILD_TOOLS%\r8.bat" (
        set "R8_BAT=%BUILD_TOOLS%\r8.bat"
    ) else (
        echo [ERROR] R8 bulunamadi.
        exit /b 1
    )
)

echo [OK] Ortam hazir.
echo.

REM ============================================================
REM CLEAN
REM ============================================================

echo [1/12] Eski build temizleniyor...

if exist gen rmdir /s /q gen
if exist obj rmdir /s /q obj
if exist r8-out rmdir /s /q r8-out
if exist lib rmdir /s /q lib

if exist compiled_res.zip del /f /q compiled_res.zip
if exist sources.txt del /f /q sources.txt
if exist classes-input.jar del /f /q classes-input.jar
if exist app-unaligned.apk del /f /q app-unaligned.apk
if exist app-aligned.apk del /f /q app-aligned.apk
if exist app-release.apk del /f /q app-release.apk

mkdir gen
mkdir obj
mkdir r8-out
mkdir lib
mkdir lib\arm64-v8a

if not exist src\main\assets mkdir src\main\assets

if exist src\main\assets\ffs_gadget del /f /q src\main\assets\ffs_gadget

echo [OK] Temizlik tamam.
echo.
''' + common_native + r'''
REM ============================================================
REM DNSMASQ
REM ============================================================

echo [3/12] dnsmasq derleniyor...

if exist "src\native\dnsmasq\src\dnsmasq.c" (

    "%CLANG%" ^
        --target=aarch64-linux-android%ANDROID_API% ^
        -O2 ^
        -fPIE ^
        -pie ^
        -DNO_IPV6 ^
        -DNO_DBUS ^
        -DVERSION=\"2.89\" ^
        -DETHER_ADDR_LEN=6 ^
        -Wno-macro-redefined ^
        src\native\dnsmasq\src\*.c ^
        -llog ^
        -o "src\main\assets\dnsmasq"

    if errorlevel 1 (
        echo [ERROR] dnsmasq derlenemedi.
        exit /b 1
    )

) else (

    if not exist "src\main\assets\dnsmasq" (
        echo [ERROR] Ne dnsmasq kaynaklari ne de asset binary bulundu.
        exit /b 1
    )

    echo [INFO] dnsmasq kaynaklari yok; mevcut asset kullaniliyor.
)

echo [OK] dnsmasq hazir.
echo.

REM ============================================================
REM RESOURCE COMPILE
REM ============================================================

echo [4/12] Resources derleniyor...

"%AAPT2%" compile ^
    --dir res ^
    -o compiled_res.zip

if errorlevel 1 (
    echo [ERROR] AAPT2 compile basarisiz.
    exit /b 1
)

echo [OK] Resources compile edildi.
echo.

REM ============================================================
REM RESOURCE LINK + ASSETS
REM ============================================================

echo [5/12] Resources ve assets link ediliyor...

"%AAPT2%" link ^
    -o app-unaligned.apk ^
    -I "%PLATFORM%" ^
    --manifest AndroidManifest.xml ^
    -R compiled_res.zip ^
    -A src\main\assets ^
    --auto-add-overlay ^
    --java gen

if errorlevel 1 (
    echo [ERROR] AAPT2 link basarisiz.
    exit /b 1
)

echo [OK] Resources ve assets APK iskeletine eklendi.
echo.

REM ============================================================
REM JAVA
REM ============================================================

echo [6/12] Java kaynaklari derleniyor...

(
    for /r src %%F in (*.java) do echo %%F
    for /r gen %%F in (*.java) do echo %%F
) > sources.txt

javac ^
    -source 8 ^
    -target 8 ^
    -encoding UTF-8 ^
    -d obj ^
    -cp "%PLATFORM%" ^
    @sources.txt

if errorlevel 1 (
    echo [ERROR] javac basarisiz.
    exit /b 1
)

echo [OK] Java derlendi.
echo.

REM ============================================================
REM CLASS JAR
REM ============================================================

echo [7/12] Class dosyalari JAR yapiliyor...

jar cf classes-input.jar -C obj .

if errorlevel 1 (
    echo [ERROR] classes-input.jar olusturulamadi.
    exit /b 1
)

echo [OK] classes-input.jar hazir.
echo.

REM ============================================================
REM R8
REM ============================================================

echo [8/12] R8 shrink + optimize + obfuscate...

if defined R8_BAT (

    call "!R8_BAT!" ^
        --release ^
        --min-api 26 ^
        --lib "%PLATFORM%" ^
        --output r8-out ^
        --pg-conf "%PROGUARD%" ^
        classes-input.jar

) else (

    java ^
        -cp "!R8_JAR!" ^
        com.android.tools.r8.R8 ^
        --release ^
        --min-api 26 ^
        --lib "%PLATFORM%" ^
        --output r8-out ^
        --pg-conf "%PROGUARD%" ^
        classes-input.jar
)

if errorlevel 1 (
    echo [ERROR] R8 basarisiz.
    exit /b 1
)

if not exist r8-out\classes.dex (
    echo [ERROR] R8 classes.dex olusturmadi.
    exit /b 1
)

echo [OK] R8 tamamlandi.
echo.

REM ============================================================
REM PACKAGE DEX + NATIVE LIBS
REM ============================================================

echo [9/12] DEX ve native kutuphaneler APK'ya ekleniyor...

copy /y r8-out\classes.dex classes.dex >nul

"%AAPT%" add app-unaligned.apk classes.dex
if errorlevel 1 exit /b 1

"%AAPT%" add app-unaligned.apk lib\arm64-v8a\libgadget.so
if errorlevel 1 exit /b 1

"%AAPT%" add app-unaligned.apk lib\arm64-v8a\libscsi.so
if errorlevel 1 exit /b 1

"%AAPT%" add app-unaligned.apk lib\arm64-v8a\libtftp.so
if errorlevel 1 exit /b 1

"%AAPT%" add app-unaligned.apk lib\arm64-v8a\libexfat.so
if errorlevel 1 exit /b 1

del /f /q classes.dex

"%AAPT%" list app-unaligned.apk | findstr /x "classes.dex" >nul
if errorlevel 1 (
    echo [ERROR] APK icinde classes.dex yok.
    exit /b 1
)

"%AAPT%" list app-unaligned.apk | findstr /x "assets/ffs_gadget" >nul
if errorlevel 1 (
    echo [ERROR] assets/ffs_gadget APK icinde yok.
    exit /b 1
)

echo [OK] APK icerigi hazir.
echo.

REM ============================================================
REM ZIPALIGN
REM ============================================================

echo [10/12] APK align ediliyor...

"%ZIPALIGN%" -f -p 4 ^
    app-unaligned.apk ^
    app-aligned.apk

if errorlevel 1 (
    echo [ERROR] zipalign basarisiz.
    exit /b 1
)

echo [OK] APK align edildi.
echo.

REM ============================================================
REM RELEASE SIGN
REM ============================================================

echo [11/12] Release APK imzalaniyor...

if not exist "%RELEASE_KEYSTORE%" (
    echo.
    echo [ERROR] Release keystore bulunamadi:
    echo %RELEASE_KEYSTORE%
    echo.
    echo Guvenlik nedeniyle release script otomatik yayin anahtari
    echo olusturmaz. RELEASE_KEYSTORE ve RELEASE_KEY_ALIAS degerlerini
    echo kendi kalici signing key'inle ayarla.
    echo.
    exit /b 1
)

set /p "RELEASE_STORE_PASS=Keystore password: "
set /p "RELEASE_KEY_PASS=Key password: "

call "%APKSIGNER%" sign ^
    --ks "%RELEASE_KEYSTORE%" ^
    --ks-key-alias "%RELEASE_KEY_ALIAS%" ^
    --ks-pass pass:"%RELEASE_STORE_PASS%" ^
    --key-pass pass:"%RELEASE_KEY_PASS%" ^
    --out app-release.apk ^
    app-aligned.apk

set "RELEASE_STORE_PASS="
set "RELEASE_KEY_PASS="

if errorlevel 1 (
    echo [ERROR] Release APK imzalanamadi.
    exit /b 1
)

echo [OK] APK imzalandi.
echo.

REM ============================================================
REM VERIFY
REM ============================================================

echo [12/12] APK dogrulaniyor...

call "%APKSIGNER%" verify ^
    --verbose ^
    --print-certs ^
    app-release.apk

if errorlevel 1 (
    echo [ERROR] APK signature verification basarisiz.
    exit /b 1
)

echo.
echo ==========================================
echo       RELEASE BUILD BASARILI
echo ==========================================
echo.
echo APK:
echo %CD%\app-release.apk
echo.
for %%A in (app-release.apk) do echo APK boyutu: %%~zA bytes
echo.

endlocal
exit /b 0