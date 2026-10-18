// === МОДАЛЬНОЕ ОКНО ===
function hideModal() {
    let m = document.getElementById('permModal');
    if (m) m.style.display = 'none';
}

// === БАЗОВЫЕ ПРЕСЕТЫ СЕНСЫ ===
function setSensitivity(speed, name) {
    if (typeof AndroidBridge === 'undefined') return;
    
    AndroidBridge.executeCommand('settings put system pointer_speed ' + speed);
    AndroidBridge.executeCommand('settings put secure pointer_speed ' + speed);
    AndroidBridge.executeCommand('settings put system pointer_acceleration 0');
    AndroidBridge.executeCommand('settings put secure pointer_acceleration 0');
    
    if (navigator.vibrate) navigator.vibrate(30);
    AndroidBridge.showToast('⚡ ' + name);

    document.querySelectorAll('.sens-card').forEach(function(card) {
        // Снимаем active только с карточек первой секции (без FixSens)
        if (card.onclick && card.onclick.toString().includes('setSensitivity')) {
            card.classList.remove('active');
        }
    });
    
    // Подсвечиваем текущую
    document.querySelectorAll('.sens-card').forEach(function(card) {
        if (card.dataset.preset === name && card.onclick && card.onclick.toString().includes('setSensitivity')) {
            card.classList.add('active');
        }
    });
}

// === ПЕРЕКЛЮЧАТЕЛИ ===
function toggleFeature(isChecked, cmd, msg) {
    if (typeof AndroidBridge === 'undefined') return;
    
    if (cmd === 'toggle') {
        AndroidBridge.toggleFloatingButton(isChecked);
    } else {
        AndroidBridge.executeCommand(cmd);
    }
    AndroidBridge.showToast(msg);
}

// === TELEPORT MODE ===
function applyTeleportMode() {
    if (typeof AndroidBridge !== 'undefined') {
        AndroidBridge.applyTeleportMode();
        if (navigator.vibrate) navigator.vibrate([100, 50, 100]);
    }
}

// === FIXSENS СЕНСА ===
function setFixSens(sharpness, noise, straighten, element) {
    if (typeof AndroidBridge !== 'undefined') {
        AndroidBridge.setFixSensParams(sharpness, noise, straighten);
        if (navigator.vibrate) navigator.vibrate(30);
    }
    
    // Снимаем active со всех FixSens карточек
    document.querySelectorAll('.sens-card').forEach(function(card) {
        if (card.onclick && card.onclick.toString().includes('setFixSens')) {
            card.classList.remove('active');
        }
    });
    
    // Подсвечиваем выбранную
    if (element) {
        element.classList.add('active');
    }
}

function toggleFixSens() {
    if (typeof AndroidBridge === 'undefined') return;
    
    var isRunning = AndroidBridge.isFixSensServiceRunning();
    var isActive = AndroidBridge.isFixSensActive();
    
    if (!isRunning) {
        AndroidBridge.openAccessibilitySettings();
        AndroidBridge.showToast('Включи Teleport в настройках');
        return;
    }
    
    AndroidBridge.toggleFixSens(!isActive);
    if (navigator.vibrate) navigator.vibrate([50, 30, 50]);
    
    // Обновляем UI через 500мс
    setTimeout(updateFixSensUI, 500);
}

function openAccessibilitySettings() {
    if (typeof AndroidBridge !== 'undefined') {
        AndroidBridge.openAccessibilitySettings();
    }
}

function updateFixSensUI() {
    if (typeof AndroidBridge === 'undefined') return;
    
    var isActive = AndroidBridge.isFixSensActive();
    var btn = document.getElementById('fixSensBtn');
    var dot = document.getElementById('fixSensDot');
    var status = document.getElementById('fixSensStatus');
    
    if (btn) {
        btn.textContent = isActive ? '🔴 ВЫКЛ СЕНСУ' : '🔵 ВКЛ СЕНСУ';
        btn.style.background = isActive
