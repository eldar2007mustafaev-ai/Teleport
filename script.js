window.onload = function() {
    checkShizuku();
};

function initShizukuConnection() {
    if (window.AndroidBridge) {
        AndroidBridge.requestShizukuPermission();
    }
}

function checkShizuku() {
    if (window.AndroidBridge) {
        AndroidBridge.checkShizukuStatus();
    }
}

function requestPermissions() {
    if (window.AndroidBridge) {
        AndroidBridge.requestShizukuPermission();
    }
}

function setShizukuStatus(isRunning, message) {
    const statusText = document.getElementById('shizuku-status');
    const statusDot = document.getElementById('status-dot');
    const welcomeModal = document.getElementById('welcome-modal');

    statusText.innerText = message;

    if (isRunning) {
        statusDot.classList.add('active');
        welcomeModal.style.display = 'none'; // Скрываем стартовый экран, если права есть
    } else {
        statusDot.classList.remove('active');
        welcomeModal.style.display = 'flex'; // Показываем экран запроса прав
    }
}

function setRatio(ratio, element) {
    document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
    element.classList.add('active');
    document.getElementById('ratio-info').innerText = 'Режим: ' + ratio;
    
    if (window.AndroidBridge) {
        AndroidBridge.applyRatio(ratio);
    }
}

function applySens() {
    const status = document.getElementById('sens-status');
    status.innerText = 'Применение настроек...';
    
    if (window.AndroidBridge) {
        AndroidBridge.applySensBoost();
        status.innerText = 'Буст отклика успешно применен!';
        status.style.color = '#2cb67d';
    }
}
