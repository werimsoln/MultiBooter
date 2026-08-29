if exist gen rmdir /s /q gen
if exist obj rmdir /s /q obj
if exist r8-out rmdir /s /q r8-out
if exist compiled_res.zip del /f /q compiled_res.zip
if exist classes.dex del /f /q classes.dex
if exist *.apk del /f /q app-*.apk
if exist *.txt del /f /q *.txt
if exist *.zip del /f /q *.zip
del /f /q *.idsig
del /f /q debug.keystore
del /f /q *.jar
del /f /q src/main/assets/dnsmasq
rmdir /s /q lib
rmdir /s /q dex-out
rmdir /s /q r8-out
