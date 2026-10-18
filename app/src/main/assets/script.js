function hideModal() {
    let m = document.getElementById('permModal');
    if (m) m.style.display = 'none';
}

function setSensitivity(speed, name) {
    AndroidBridge.executeCommand('settings put system pointer_speed ' + speed);
    AndroidBridge.showToast('Пресет: ' + name);
}

function toggleFeature(isChecked, cmd, msg) {
    if (isChecked) {
        AndroidBridge.executeCommand(cmd);
        AndroidBridge.showToast(msg);
    }
}
