// === МОДАЛЬНОЕ ОКНО ===
function hideModal() {
    var m = document.getElementById('permModal');
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
    
    document.querySelectorAll('.sens-card').forEach(function(card) {
        if (card.onclick && card.onclick.toString().includes('setFixSens')) {
            card.classList.remove('active');
        }
    });
    
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
        btn.textContent = isActive ? ' ВЫКЛ СЕНСУ' : ' ВКЛ СЕНСУ';
        btn.style.background = isActive 
            ? 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)' 
            : 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)';
    }
    if (dot) {
        dot.style.backgroundColor = isActive ? '#ef4444' : '#34d399';
        dot.style.boxShadow = isActive ? '0 0 8px #ef4444' : '0 0 8px #34d399';
    }
    if (status) {
        status.textContent = isActive ? 'FixSens активен' : 'Сервис не активен';
        status.style.color = isActive ? '#ef4444' : '#94a3b8';
    }
}

// === ПРОВЕРКА SHIZUKU ===
function checkShizuku() {
    if (typeof AndroidBridge !== 'undefined') {
        var status = AndroidBridge.getShizukuStatus();
        AndroidBridge.showToast(status);
    } else {
        alert('AndroidBridge не найден');
    }
}

// === ТЕМА ===
function applyTheme(t) {
    document.documentElement.setAttribute('data-theme', t);
    try { localStorage.setItem('theme', t); } catch (e) {}
}

function toggleTheme() {
    var cur = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    applyTheme(cur);
}

(function () {
    var saved = 'dark';
    try { saved = localStorage.getItem('theme') || 'dark'; } catch (e) {}
    applyTheme(saved);
})();

// === ПРОВЕРКА РАЗРЕШЕНИЙ ПРИ ЗАГРУЗКЕ ===
window.addEventListener('load', function() {
    if (typeof AndroidBridge !== 'undefined') {
        var hasPermission = AndroidBridge.hasOverlayPermission();
        var modal = document.getElementById('permModal');
        if (modal) {
            modal.style.display = hasPermission ? 'none' : 'flex';
        }
        
        setTimeout(updateFixSensUI, 1000);
    }
});
