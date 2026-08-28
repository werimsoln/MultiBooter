@echo off
setlocal EnableExtensions EnableDelayedExpansion

echo ==========================================
echo       MultiBooter RELEASE BUILD
echo ==========================================
echo.

REM ============================================================
REM PATHS
REM ============================================================

set "ANDROID_HOME=C:\Android\sdk"
set "PLATFORM=%ANDROID_HOME%\platforms\android-34\android.jar"
set "BUILD_TOOLS=%ANDROID_HOME%\build-tools\34.0.0"
set "R8_JAR=%ANDROID_HOME%\r8\r8.jar"

set "SCRIPT_DIR=%~dp0"
set "PROGUARD=%SCRIPT_DIR%proguard-rules.pro"

REM ============================================================
REM ENVIRONMENT CHECK
REM ============================================================

echo [0/11] Ortam kontrol ediliyor...

if not exist "%PLATFORM%" (
    echo [ERROR] android.jar bulunamadi:
    echo %PLATFORM%
    exit /b 1
)

if not exist "%BUILD_TOOLS%\aapt2.exe" (
    echo [ERROR] aapt2.exe bulunamadi:
    echo %BUILD_TOOLS%\aapt2.exe
    exit /b 1
)

if not exist "%BUILD_TOOLS%\aapt.exe" (
    echo [ERROR] aapt.exe bulunamadi:
    echo %BUILD_TOOLS%\aapt.exe
    exit /b 1
)

if not exist "%BUILD_TOOLS%\zipalign.exe" (
    echo [ERROR] zipalign.exe bulunamadi:
    echo %BUILD_TOOLS%\zipalign.exe
    exit /b 1
)

if not exist "%BUILD_TOOLS%\apksigner.bat" (
    echo [ERROR] apksigner.bat bulunamadi:
    echo %BUILD_TOOLS%\apksigner.bat
    exit /b 1
)

where javac >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] javac bulunamadi.
    exit /b 1
)

where jar >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] jar.exe bulunamadi.
    exit /b 1
)

where keytool >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] keytool bulunamadi.
    exit /b 1
)

echo [OK] Android SDK kontrolu tamam.
echo [OK] JDK kontrolu tamam.
echo.

REM ============================================================
REM R8 CHECK
REM ============================================================

if not exist "%R8_JAR%" (

    if exist "%BUILD_TOOLS%\lib\r8.jar" (
        set "R8_JAR=%BUILD_TOOLS%\lib\r8.jar"
        echo [OK] Build Tools icindeki R8 kullanilacak.
    ) else (

        if exist "%BUILD_TOOLS%\r8.bat" (
            set "R8_BAT=%BUILD_TOOLS%\r8.bat"
            echo [OK] Build Tools R8 bulundu.
        ) else (
            echo [ERROR] R8 bulunamadi.
            echo.
            echo R8 jar:
            echo %R8_JAR%
            echo.
            echo Once setup_r8.bat dosyasini calistir.
            echo.
            exit /b 1
        )
    )
)

if defined R8_BAT (
    echo [OK] R8: !R8_BAT!
) else (
    echo [OK] R8: !R8_JAR!
)

echo.

REM ============================================================
REM CLEAN
REM ============================================================

echo [1/11] Eski build temizleniyor...

if exist gen rmdir /s /q gen
if exist obj rmdir /s /q obj
if exist r8-out rmdir /s /q r8-out
if exist d8-out rmdir /s /q d8-out

if exist compiled_res.zip del /f /q compiled_res.zip
if exist classes-input.jar del /f /q classes-input.jar
if exist classes.dex del /f /q classes.dex

if exist app-unaligned.apk del /f /q app-unaligned.apk
if exist app-aligned.apk del /f /q app-aligned.apk
if exist app-release.apk del /f /q app-release.apk

if exist sources.txt del /f /q sources.txt

mkdir gen
mkdir obj
mkdir r8-out

echo [OK] Temizlik tamam.
echo.

REM ============================================================
REM RESOURCE COMPILE
REM ============================================================

echo [2/11] Resources derleniyor...

"%BUILD_TOOLS%\aapt2.exe" compile ^
    --dir res ^
    -o compiled_res.zip

if %errorlevel% neq 0 (
    echo [ERROR] AAPT2 resource compile basarisiz.
    exit /b %errorlevel%
)

echo [OK] Resources compile edildi.
echo.

REM ============================================================
REM RESOURCE LINK
REM ============================================================

echo [3/11] Android resources link ediliyor...

"%BUILD_TOOLS%\aapt2.exe" link ^
    -o app-unaligned.apk ^
    -I "%PLATFORM%" ^
    --manifest AndroidManifest.xml ^
    -R compiled_res.zip ^
    --auto-add-overlay ^
    --java gen

if %errorlevel% neq 0 (
    echo [ERROR] AAPT2 link basarisiz.
    exit /b %errorlevel%
)

echo [OK] Resources link edildi.
echo.

REM ============================================================
REM JAVA SOURCE LIST
REM ============================================================

echo [4/11] Java kaynaklari listeleniyor...

dir /s /b src\*.java gen\*.java > sources.txt

if %errorlevel% neq 0 (
    echo [ERROR] Java kaynaklari bulunamadi.
    exit /b %errorlevel%
)

if not exist sources.txt (
    echo [ERROR] sources.txt olusturulamadi.
    exit /b 1
)

echo [OK] Java kaynaklari listelendi.
echo.

REM ============================================================
REM JAVAC
REM ============================================================

echo [5/11] Java derleniyor...

javac ^
    -source 8 ^
    -target 8 ^
    -encoding UTF-8 ^
    -d obj ^
    -cp "%PLATFORM%" ^
    @sources.txt

if %errorlevel% neq 0 (
    echo [ERROR] javac basarisiz.
    exit /b %errorlevel%
)

echo [OK] javac compile tamamlandi.
echo.

REM ============================================================
REM CLASS -> JAR
REM ============================================================

echo [6/11] Java class dosyalari JAR yapiliyor...

if not exist obj (
    echo [ERROR] obj klasoru bulunamadi.
    exit /b 1
)

jar cf classes-input.jar -C obj .

if %errorlevel% neq 0 (
    echo [ERROR] classes-input.jar olusturulamadi.
    exit /b %errorlevel%
)

if not exist classes-input.jar (
    echo [ERROR] classes-input.jar bulunamadi.
    exit /b 1
)

echo [OK] classes-input.jar hazir.
echo.

REM ============================================================
REM R8
REM ============================================================

echo [7/11] R8 shrink + optimize + obfuscate basliyor...
echo.

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

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] R8 basarisiz.
    exit /b %errorlevel%
)

echo.
echo [OK] R8 tamamlandi.
echo.

REM ============================================================
REM DEX CHECK
REM ============================================================

if not exist r8-out\classes.dex (
    echo [ERROR] R8 classes.dex olusturmadi.
    echo.
    echo R8 output:
    dir /s /b r8-out
    exit /b 1
)

copy /y r8-out\classes.dex classes.dex >nul

if %errorlevel% neq 0 (
    echo [ERROR] classes.dex kopyalanamadi.
    exit /b %errorlevel%
)

if not exist classes.dex (
    echo [ERROR] classes.dex bulunamadi.
    exit /b 1
)

echo [OK] classes.dex hazir.
echo.

REM ============================================================
REM ADD DEX
REM ============================================================

echo [8/11] DEX APK'ya ekleniyor...

"%BUILD_TOOLS%\aapt.exe" add app-unaligned.apk classes.dex

if %errorlevel% neq 0 (
    echo [ERROR] classes.dex APK'ya eklenemedi.
    exit /b %errorlevel%
)

echo [OK] DEX APK'ya eklendi.
echo.

REM ============================================================
REM VERIFY DEX ENTRY
REM ============================================================

echo [INFO] APK DEX kontrol ediliyor...

jar tf app-unaligned.apk | findstr /x "classes.dex" >nul

if %errorlevel% neq 0 (
    echo [ERROR] APK icinde classes.dex bulunamadi.
    exit /b 1
)

echo [OK] classes.dex APK icinde mevcut.
echo.

REM ============================================================
REM ZIPALIGN
REM ============================================================

echo [9/11] APK align ediliyor...

"%BUILD_TOOLS%\zipalign.exe" ^
    -f ^
    -p ^
    4 ^
    app-unaligned.apk ^
    app-aligned.apk

if %errorlevel% neq 0 (
    echo [ERROR] zipalign basarisiz.
    exit /b %errorlevel%
)

echo [OK] APK align edildi.
echo.

REM ============================================================
REM KEYSTORE
REM ============================================================

echo [10/11] Release APK imzalaniyor...

if not exist debug.keystore (

    echo [INFO] debug.keystore bulunamadi, olusturuluyor...

    keytool -genkeypair ^
        -v ^
        -keystore debug.keystore ^
        -alias androiddebugkey ^
        -keyalg RSA ^
        -keysize 2048 ^
        -validity 10000 ^
        -storepass android ^
        -keypass android ^
        -dname "CN=Test, OU=Test, O=Test, L=Test, S=Test, C=TR"

    if !errorlevel! neq 0 (
        echo [ERROR] Keystore olusturulamadi.
        exit /b !errorlevel!
    )
)

call "%BUILD_TOOLS%\apksigner.bat" sign ^
    --ks debug.keystore ^
    --ks-pass pass:android ^
    --key-pass pass:android ^
    --out app-release.apk ^
    app-aligned.apk

if %errorlevel% neq 0 (
    echo [ERROR] APK imzalanamadi.
    exit /b %errorlevel%
)

echo [OK] APK imzalandi.
echo.

REM ============================================================
REM VERIFY
REM ============================================================

echo [11/11] APK dogrulaniyor...

call "%BUILD_TOOLS%\apksigner.bat" verify ^
    --verbose ^
    app-release.apk

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] APK signature verification basarisiz.
    exit /b %errorlevel%
)

echo.
echo ==========================================
echo       RELEASE BUILD BASARILI
echo ==========================================
echo.
echo APK:
echo %CD%\app-release.apk
echo.

for %%A in (app-release.apk) do (
    echo APK boyutu: %%~zA bytes
)

echo.
echo ==========================================
echo       APK HAZIR
echo ==========================================
echo.

endlocal