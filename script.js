function setRatio(ratio, element) {
    document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
    element.classList.add('active');
    document.getElementById('ratio-info').innerText = 'Режим: ' + ratio + ' активирован';
}

function applySens() {
    const status = document.getElementById('sens-status');
    status.innerText = 'Применение параметров сенсы...';
    setTimeout(() => {
        status.innerText = 'Сенса успешно телепортирована и оптимизирована!';
    }, 500);
}
