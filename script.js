function setRatio(ratio, element) {
    document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
    element.classList.add('active');
    document.getElementById('ratio-info').innerText = 'Режим: ' + ratio + ' активирован';
    
    // Передаем команду в Android через Shizuku
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
