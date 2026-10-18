function hideModal() {
    let m = document.getElementById('permModal');
    if (m) m.style.display = 'none';
}

function setSensitivity(speed, name) {
    AndroidBridge.executeCommand('settings put system pointer_speed ' + speed);
    AndroidBridge.executeCommand('settings put secure pointer_speed ' + speed);
    AndroidBridge.executeCommand('settings put system pointer_acceleration 0');
    AndroidBridge.executeCommand('settings put secure pointer_acceleration 0');
    
    if (navigator.vibrate) navigator.vibrate(30);
    AndroidBridge.showToast(' ' + name);

    document.querySelectorAll('.sens-card').forEach(function(card) {
        card.classList.toggle('active', card.dataset.preset === name);
    });
}

function toggleFeature(isChecked, cmd, msg) {
    if (cmd === 'toggle') {
        AndroidBridge.toggleFloatingButton(isChecked);
    } else {
        AndroidBridge.executeCommand(cmd);
    }
    AndroidBridge.showToast(msg);
}

// МАКСИМАЛЬНЫЙ TELEPORT MODE — сенса телепортируется
function applyTeleportMode() {
    if (typeof AndroidBridge !== 'undefined') {
        AndroidBridge.applyTeleportMode();
        if (navigator.vibrate) navigator.vibrate([100, 50, 100]);
    }
    
    // Подсветка карточки
    document.querySelectorAll('.sens-card').forEach(function(card) {
        card.classList.toggle('active', card.dataset.preset === 'Телепорт');
    });
}

function checkShizuku() {
    if (typeof AndroidBridge !== 'undefined') {
        var status = AndroidBridge.getShizukuStatus();
        AndroidBridge.showToast(status);
    } else {
        AndroidBridge.showToast('AndroidBridge не найден');
    }
}

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

window.addEventListener('load', function() {
    if (typeof AndroidBridge !== 'undefined') {
        var hasPermission = AndroidBridge.hasOverlayPermission();
        if (!hasPermission) {
            setTimeout(function() {
                var modal = document.getElementById('permModal');
                if (modal) modal.style.display = 'flex';
            }, 1000);
        }
    }
});
