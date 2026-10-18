function hideModal() {
    let m = document.getElementById('permModal');
    if (m) m.style.display = 'none';
}

function setSensitivity(speed, name) {
    AndroidBridge.executeCommand('settings put system pointer_speed ' + speed);
    AndroidBridge.showToast('Пресет: ' + name);

    // подсветка активного пресета
    document.querySelectorAll('.sens-card').forEach(function(card) {
        card.classList.toggle('active', card.dataset.preset === name);
    });
}

function toggleFeature(isChecked, cmd, msg) {
    if (isChecked) {
        AndroidBridge.executeCommand(cmd);
        AndroidBridge.showToast(msg);
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

// восстановление темы при запуске
(function () {
    var saved = 'dark';
    try { saved = localStorage.getItem('theme') || 'dark'; } catch (e) {}
    applyTheme(saved);
})();
