window.onload = function() {
    checkShizuku();
};

function checkShizuku() {
    if (window.AndroidBridge) {
        AndroidBridge.checkShizukuStatus();
    }
}

function requestShizuku() {
    if (window.AndroidBridge) {
        AndroidBridge.requestShizukuPermission();
    }
}

function updateUIStatus(isActive, title, sub) {
    const dot = document.getElementById('dot');
    const titleEl = document.getElementById('status-title');
    const subEl = document.getElementById('status-sub');

    titleEl.innerText = title;
    subEl.innerText = sub;

    if (isActive) {
        dot.classList.add('active');
    } else {
        dot.classList.remove('active');
    }
}

function setRatio(ratio, element) {
    document.querySelectorAll('.btn-group .btn').forEach(b => b.classList.remove('active'));
    element.classList.add('active');
    if (window.AndroidBridge) {
        AndroidBridge.applyRatio(ratio);
    }
}

let sensActive = false;
function toggleSens() {
    sensActive = !sensActive;
    const btn = document.getElementById('sens-btn');
    if (sensActive) {
        btn.classList.add('active');
        btn.innerText = 'РАБОТАЕТ';
    } else {
        btn.classList.remove('active');
        btn.innerText = 'ВКЛ';
    }
    if (window.AndroidBridge) {
        AndroidBridge.applySensBoost(sensActive);
    }
}
