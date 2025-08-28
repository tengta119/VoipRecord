adb root
adb remount
adb disable-verity
adb root
adb remount
adb shell umount -l /system_ext/priv-app/
adb shell umount -l /system_ext/etc/permissions/
adb shell mkdir /system_ext/priv-app/voiprecord
adb push app/build/outputs/apk/debug/app-debug.apk /system_ext/priv-app/voiprecord/
adb push privapp_allowlist_com.example.voiprecord.xml /system_ext/etc/permissions/
