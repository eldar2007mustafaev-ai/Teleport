function hideModal() {
    let m = document.getElementById('permModal');
    if (m) m.style.display = 'none';
}

function setSensitivity(speed, name) {
    // Усиленные значения для максимальной отзывчивости
    AndroidBridge.executeCommand('settings put system pointer_speed ' + speed);
    AndroidBridge.executeCommand('settings put secure pointer_speed ' + speed);
    // Отключаем акселерацию для мгновенного отклика
    AndroidBridge.executeCommand('settings put system pointer_acceleration 0');
    AndroidBridge.showToast('⚡ Пресет: ' + name);

    // подсветка активного пресета
    document.querySelectorAll('.sens-card').forEach(function(card) {
        card.classList.toggle('active', card.dataset.preset === name);
    });
}

function toggleFeature(isChecked, cmd, msg) {
    // Выполняем команду независимо от состояния (для переключателей)
    AndroidBridge.executeCommand(cmd);
    AndroidBridge.showToast(msg);
}

function applyTheme(t) {
    document.documentElement.setAttribute('data-theme', t);
    try { localStorage.setItem('theme', t); } catch (e) {}
}

function toggleTheme() {
    var cur = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    applyTheme(cur);
}

// восстановление темы при запуске
(function () {
    var saved = 'dark';
    try { saved = localStorage.getItem('theme') || 'dark'; } catch (e) {}
    applyTheme(saved);
})();

// Проверка разрешений при загрузке
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
