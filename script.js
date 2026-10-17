// Автопроверка при запуске
window.onload = function() {
    checkShizuku();
};

function checkShizuku() {
    if (window.AndroidBridge) {
        window.AndroidBridge.checkShizukuStatus();
    } else {
        updateStatusUI(false, "Мост с Android не найден");
    }
}

// Эта функция вызывается из Java, когда статус известен
function setShizukuStatus(isRunning, message) {
    const card = document.getElementById('status-card');
    const statusText = document.getElementById('shizuku-status');
    const title = card.querySelector('.card-title');

    if (isRunning) {
        card.style.borderColor = '#00ff88';
        title.style.color = '#00ff88';
        statusText.style.color = '#00ff88';
        statusText.innerText = message;
    } else {
        card.style.borderColor = '#ff3333';
        title.style.color = '#ff3333';
        statusText.style.color = '#ff3333';
        statusText.innerText = message;
    }
}

function setRatio(ratio, element) {
    document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
    element.classList.add('active');
    document.getElementById('ratio-info').innerText = 'Режим: ' + ratio + ' активирован';
    
    if (window.AndroidBridge) {
        AndroidBridge.applyRatio(ratio);
    }
}

function applySens() {
    const status = document.getElementById('sens-status');
    status.innerText = 'Телепортация сенсы через Shizuku...';
    
    if (window.AndroidBridge) {
        AndroidBridge.applySensBoost();
        status.innerText = 'Сенса успешно телепортирована!';
    } else {
        status.innerText = 'Ошибка: мост с Android не найден';
    }
}
