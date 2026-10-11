while ($true) {
    & adb reverse tcp:8080 tcp:8080
    Start-Sleep -Seconds 3
}
