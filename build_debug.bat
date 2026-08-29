@echo off
set ANDROID_HOME=C:\Android\sdk
set PLATFORM=%ANDROID_HOME%\platforms\android-34\android.jar
set BUILD_TOOLS=%ANDROID_HOME%\build-tools\34.0.0
set NDK_BIN=%ANDROID_HOME%\ndk\26.1.10909125\toolchains\llvm\prebuilt\windows-x86_64\bin
set NDK_MAKE=%ANDROID_HOME%\ndk\26.1.10909125\prebuilt\windows-x86_64\bin\make.exe
setlocal enabledelayedexpansion

if exist gen rmdir /s /q gen
if exist obj rmdir /s /q obj
if exist lib rmdir /s /q lib
if exist compiled_res.zip del /f /q compiled_res.zip
if exist classes.dex del /f /q classes.dex
if exist app-*.apk del /f /q app-*.apk
if exist sources.txt del /f /q sources.txt

mkdir gen
mkdir obj
mkdir lib\arm64-v8a
if not exist src\main\assets mkdir src\main\assets

%NDK_BIN%\clang.exe --target=aarch64-linux-android34 -shared -fPIC jni\libgadget.c -o lib\arm64-v8a\libgadget.so
if %errorlevel% neq 0 exit /b %errorlevel%

%NDK_BIN%\clang.exe --target=aarch64-linux-android34 -shared -fPIC jni\libscsi.c -o lib\arm64-v8a\libscsi.so
if %errorlevel% neq 0 exit /b %errorlevel%

%NDK_BIN%\clang.exe --target=aarch64-linux-android34 -shared -fPIC jni\libtftp.c -o lib\arm64-v8a\libtftp.so
if %errorlevel% neq 0 exit /b %errorlevel%

echo 4. dnsmasq C dosyalari dogrudan derleniyor (Makefile bypass edildi)...
%NDK_BIN%\clang.exe --target=aarch64-linux-android34 -O2 -DNO_IPV6 -DNO_DBUS -DVERSION=\"2.89\" -DETHER_ADDR_LEN=6 -Wno-macro-redefined src\native\dnsmasq\src\*.c -llog -o src\main\assets\dnsmasq
if %errorlevel% neq 0 (
    echo [HATA] dnsmasq derlenirken sorun olustu!
    exit /b %errorlevel%
)

%BUILD_TOOLS%\aapt2.exe compile --dir res -o compiled_res.zip
if %errorlevel% neq 0 exit /b %errorlevel%

%BUILD_TOOLS%\aapt2.exe link -o app-unaligned.apk -I %PLATFORM% --manifest AndroidManifest.xml -R compiled_res.zip -A src\main\assets --auto-add-overlay --java gen
if %errorlevel% neq 0 exit /b %errorlevel%

dir /s /b src\*.java gen\*.java > sources.txt
javac -source 8 -target 8 -d obj -cp "%PLATFORM%" @sources.txt
if %errorlevel% neq 0 exit /b %errorlevel%

set "CLASS_FILES="
for /r obj %%i in (*.class) do set "CLASS_FILES=!CLASS_FILES! "%%i""
call %BUILD_TOOLS%\d8.bat --min-api 26 --lib %PLATFORM% --output . !CLASS_FILES!
if %errorlevel% neq 0 exit /b %errorlevel%

%BUILD_TOOLS%\aapt.exe add app-unaligned.apk classes.dex
if %errorlevel% neq 0 exit /b %errorlevel%

%BUILD_TOOLS%\aapt.exe add app-unaligned.apk lib\arm64-v8a\libgadget.so
if %errorlevel% neq 0 exit /b %errorlevel%

%BUILD_TOOLS%\aapt.exe add app-unaligned.apk lib\arm64-v8a\libscsi.so
if %errorlevel% neq 0 exit /b %errorlevel%


%BUILD_TOOLS%\zipalign.exe -f -p 4 app-unaligned.apk app-aligned.apk
if %errorlevel% neq 0 exit /b %errorlevel%

if not exist debug.keystore (
    keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Test, OU=Test, O=Test, L=Test, S=Test, C=TR"
)

call %BUILD_TOOLS%\apksigner.bat sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out app-debug.apk app-aligned.apk
if %errorlevel% neq 0 exit /b %errorlevel%