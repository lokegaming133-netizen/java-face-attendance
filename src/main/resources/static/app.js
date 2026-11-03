const $ = id => document.getElementById(id);
let csrf, stream, students = [];
let scanGeneration = 0, scanTimer = null, scanMisses = 0;

function tell(s) { $('message').textContent = s; }

async function api(path, options = {}) {
    options.headers = { ...options.headers };
    if (options.method && options.method !== 'GET') {
        if (!csrf) throw Error('Please wait for page initialization');
        options.headers[csrf.headerName] = csrf.token;
    }
    const response = await fetch('/api' + path, options);
    if (response.redirected) {
        location.href = '/login';
        throw Error('Please sign in');
    }
    const text = await response.text();
    let data;
    try { data = JSON.parse(text); } catch { data = { message: text }; }
    if (!response.ok) throw Error(data.message || 'Request failed');
    return data;
}

function json(method, body) {
    return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

// H2 returns uppercase column names; PostgreSQL returns lowercase.
// Normalize API rows so the same UI works with both databases.
function normalizeColumns(row) {
    return Object.fromEntries(Object.entries(row).map(([key, value]) => [key.toUpperCase(), value]));
}

async function refresh() {
    students = (await api('/students')).map(normalizeColumns);
    const selected = $('student').value;
    $('student').replaceChildren();
    for (const student of students) {
        const option = document.createElement('option');
        option.value = student.ID;
        option.textContent = `${student.NAME} / ${student.ROLL} (${student.SAMPLES} smart samples; ${student.LEGACY_SAMPLES} old samples)`;
        $('student').append(option);
    }
    if (students.some(s => String(s.ID) === selected)) $('student').value = selected;
    syncGenderSelection();

    const records = (await api('/attendance')).map(normalizeColumns);
    $('records').replaceChildren();
    for (const record of records) {
        const tr = document.createElement('tr');
        for (const value of [record.NAME, record.ROLL, record.GENDER || 'Not specified', record.SESSION_NAME, record.MARKED_AT]) {
            const cell = document.createElement('td');
            cell.textContent = value;
            tr.append(cell);
        }
        const cell = document.createElement('td');
        const button = document.createElement('button');
        button.textContent = 'Remove';
        button.onclick = () => run(async () => {
            if (confirm('Remove this attendance record?')) {
                await api('/attendance?student=' + record.STUDENT + '&session=' + encodeURIComponent(record.SESSION_NAME), { method: 'DELETE' });
                await refresh();
            }
        });
        cell.append(button);
        tr.append(cell);
        $('records').append(tr);
    }
}

function syncGenderSelection() {
    const student = students.find(s => String(s.ID) === $('student').value);
    $('existing-gender').value = student?.GENDER || 'Not specified';
}

async function run(fn) {
    try { await fn(); } catch (e) { tell(e.message); }
}

function bind(id, fn) {
    $(id).onclick = () => run(async () => {
        $(id).disabled = true;
        try { await fn(); } finally { $(id).disabled = false; }
    });
}

/* Crop webcam as CSS object-fit:cover does, so face rectangles align with video.
   Optional rotation helps the existing frontal Haar detector with modest head tilt.
   This does not fix full sideways/profile faces or guarantee a correct identity. */
function frame(angle = 0) {
    return new Promise((resolve, reject) => {
        const video = $('video');
        if (!stream || !video.videoWidth || !video.videoHeight) {
            return reject(Error('Start camera and wait for preview'));
        }
        const canvas = document.createElement('canvas');
        canvas.width = 640;
        canvas.height = 480;
        const ctx = canvas.getContext('2d');
        const cropWidth = Math.min(video.videoWidth, video.videoHeight * 4 / 3);
        const cropHeight = Math.min(video.videoHeight, video.videoWidth * 3 / 4);
        const cropX = (video.videoWidth - cropWidth) / 2;
        const cropY = (video.videoHeight - cropHeight) / 2;
        ctx.fillStyle = '#d5d5d5';
        ctx.fillRect(0, 0, 640, 480);
        ctx.save();
        ctx.translate(320, 240);
        ctx.rotate(angle * Math.PI / 180);
        ctx.drawImage(video, cropX, cropY, cropWidth, cropHeight, -320, -240, 640, 480);
        ctx.restore();
        canvas.toBlob(blob => blob ? resolve(blob) : reject(Error('Capture failed')), 'image/jpeg', 0.86);
    });
}

async function imageBody(angle = 0) {
    const form = new FormData();
    form.append('image', await frame(angle), 'frame.jpg');
    return form;
}

function session() {
    const value = $('session').value.trim();
    if (!value) throw Error('Enter a unique date + class + session');
    return value;
}

/* One request at a time (about one scan per second); no frames are stored. */
function stopLiveScan() {
    scanGeneration++;
    clearTimeout(scanTimer);
    scanTimer = null;
    scanMisses = 0;
    $('face-overlay').replaceChildren();
    $('live-status').textContent = 'Camera off';
}

function remapFace(face, angle, width, height) {
    // Inverse-transform the rectangle from a rotated webcam image.
    const radians = angle * Math.PI / 180;
    const cos = Math.cos(radians), sin = Math.sin(radians);
    const cx = width / 2, cy = height / 2;
    const corners = [
        [face.x, face.y],
        [face.x + face.width, face.y],
        [face.x, face.y + face.height],
        [face.x + face.width, face.y + face.height]
    ].map(([x, y]) => {
        const dx = x - cx, dy = y - cy;
        return [cx + cos * dx + sin * dy, cy - sin * dx + cos * dy];
    });
    const xs = corners.map(p => p[0]), ys = corners.map(p => p[1]);
    return { x: Math.min(...xs), y: Math.min(...ys),
        width: Math.max(...xs) - Math.min(...xs),
        height: Math.max(...ys) - Math.min(...ys) };
}

function updateBoxes(result, angle = 0) {
    const overlay = $('face-overlay');
    const faces = result.faces || [];
    const width = Number(result.imageWidth) || 640;
    const height = Number(result.imageHeight) || 480;
    if (!faces.length) {
        scanMisses++;
        if (scanMisses >= 2) overlay.replaceChildren();
        $('live-status').textContent = 'Looking for a face...';
        return;
    }
    scanMisses = 0;
    $('live-status').textContent = faces.length > 1
        ? 'More than one face: show one face to recognize'
        : faces[0].student
            ? 'Face detected · Possible match: teacher must verify'
            : 'Face detected · Unknown student: capture 5–10 face samples';

    // Reuse nodes for smooth box motion without visual flicker.
    if (overlay.children.length !== faces.length) {
        overlay.replaceChildren();
        for (let i = 0; i < faces.length; i++) {
            const box = document.createElement('div');
            box.className = 'face-box';
            const tag = document.createElement('span');
            tag.className = 'face-tag';
            box.append(tag);
            overlay.append(box);
        }
    }
    for (let i = 0; i < faces.length; i++) {
        const face = faces[i];
        const box = overlay.children[i];
        const rect = remapFace(face, angle, width, height);
        // Larger than detected face, scaled to each person's own face size.
        const left = Math.max(0, rect.x - rect.width * .16);
        const top = Math.max(0, rect.y - rect.height * .21);
        const right = Math.min(width, rect.x + rect.width * 1.16);
        const bottom = Math.min(height, rect.y + rect.height * 1.15);
        box.style.left = left / width * 100 + '%';
        box.style.top = top / height * 100 + '%';
        box.style.width = Math.max(0, right - left) / width * 100 + '%';
        box.style.height = Math.max(0, bottom - top) / height * 100 + '%';
        // Nothing except NAME is shown over a matched face. Unknown shows no tag.
        box.firstChild.textContent = face.student && faces.length === 1
            ? String(face.name || 'Student') : '';
    }
}

async function scanCamera(generation) {
    if (!stream || scanGeneration !== generation) return;
    try {
        const body = await imageBody();
        const initial = await api('/live', { method: 'POST', body });
        let display = initial, angle = 0;
        const singleUnknown = initial.faces?.length === 1 && !initial.faces[0].student;
        // Only retry if no face or one unknown face. Never try to identify a crowd.
        if ((initial.faces?.length === 0 || singleUnknown) && scanGeneration === generation) {
            for (const tilt of [14, -14]) {
                if (!stream || scanGeneration !== generation) break;
                const body = await imageBody(tilt);
                const alternate = await api('/live', { method: 'POST', body });
                if (alternate.faces?.length === 1 && alternate.faces[0].student) {
                    display = alternate; angle = tilt; break;
                }
                if (!initial.faces?.length && !display.faces?.length && alternate.faces?.length === 1) {
                    display = alternate; angle = tilt;
                }
            }
        }
        if (stream && scanGeneration === generation) updateBoxes(display, angle);
    } catch (error) {
        if (stream && scanGeneration === generation) {
            $('live-status').textContent = 'Live detection unavailable';
            if (!String(error.message).includes('Start camera')) tell('Live detection: ' + error.message);
        }
    } finally {
        if (stream && scanGeneration === generation) {
            scanTimer = setTimeout(() => scanCamera(generation), 500);
        }
    }
}

bind('start', async () => {
    stopLiveScan();
    if (stream) stream.getTracks().forEach(track => track.stop());
    stream = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480 }, audio: false });
    $('video').srcObject = stream;
    $('live-status').textContent = 'Starting face detection...';
    tell('Camera ready - face tracking enabled');
    const generation = scanGeneration;
    scanTimer = setTimeout(() => scanCamera(generation), 450);
});

bind('stop', async () => {
    stopLiveScan();
    stream?.getTracks().forEach(track => track.stop());
    stream = null;
    $('video').srcObject = null;
    tell('Camera stopped');
});

$('registration').onsubmit = e => {
    e.preventDefault();
    run(async () => {
        const roll = $('roll').value.trim();
        const result = await api('/students', json('POST', {
            name: $('name').value, roll,
            gender: $('gender').value, consent: String($('consent').checked)
        }));
        await refresh();
        $('student').value = students.find(s => s.ROLL === roll)?.ID;
        $('registration').reset();
        tell(result.message);
    });
};

$('student').addEventListener('change', syncGenderSelection);

bind('save-gender', async () => {
    const selected = $('student').value;
    if (!selected) throw Error('Select a student first');
    const result = await api('/students/' + selected + '/gender',
        json('PUT', { gender: $('existing-gender').value }));
    await refresh();
    tell(result.message);
});

bind('sample', async () => {
    if (!$('student').value) throw Error('Register a student first');
    const path = '/students/' + $('student').value + '/samples';
    let result;
    try {
        result = await api(path, { method: 'POST', body: await imageBody() });
    } catch (error) {
        if (!String(error.message).includes('Exactly one clear face required')) throw error;
        let retryError = error;
        for (const angle of [14, -14]) {
            try {
                result = await api(path, { method: 'POST', body: await imageBody(angle) });
                break;
            } catch (e) {
                retryError = e;
                if (!String(e.message).includes('Exactly one clear face required')) throw e;
            }
        }
        if (!result) throw retryError;
    }
    await refresh();
    tell(result.message);
});

bind('recognize', async () => {
    const currentSession = session();
    $('candidate').hidden = true;
    const body = await imageBody();
    body.append('session', currentSession);
    let result = await api('/recognize', { method: 'POST', body });
    if (!result.candidate && String(result.message).includes('Unknown face')) {
        // Another live frame often catches open eyes / less motion blur.
        for (let retry = 0; retry < 2 && !result.candidate; retry++) {
            await new Promise(resolve => setTimeout(resolve, 220));
            const next = await imageBody();
            next.append('session', currentSession);
            const candidate = await api('/recognize', { method: 'POST', body: next });
            if (candidate.candidate) result = candidate;
        }
    }
    if (!result.candidate && String(result.message).includes('Unknown face')) {
        for (const angle of [14, -14]) {
            const turned = await imageBody(angle);
            turned.append('session', currentSession);
            const candidate = await api('/recognize', { method: 'POST', body: turned });
            if (candidate.candidate) { result = candidate; break; }
        }
    }
    tell(result.message);
    if (result.candidate) {
        const student = students.find(s => s.ID === result.candidate);
        const box = $('candidate');
        box.replaceChildren(document.createTextNode(`Verify candidate: ${student?.NAME || result.candidate} `));
        const button = document.createElement('button');
        button.textContent = 'Confirm attendance';
        button.onclick = () => run(async () => {
            button.disabled = true;
            try {
                const saved = await api('/mark', json('POST', { student: String(result.candidate), session: currentSession }));
                tell(saved.message);
                box.hidden = true;
                await refresh();
            } finally { button.disabled = false; }
        });
        box.append(button);
        box.hidden = false;
    }
});

bind('manual', async () => {
    if (!$('student').value) throw Error('Select a student');
    if (confirm('Verify selected student is present?')) {
        const result = await api('/mark', json('POST', { student: $('student').value, session: session() }));
        tell(result.message);
        await refresh();
    }
});

bind('delete', async () => {
    if (!$('student').value) throw Error('Select a student');
    if (confirm('Delete student, all face samples and attendance?')) {
        await api('/students/' + $('student').value, { method: 'DELETE' });
        await refresh();
        tell('Student data deleted');
    }
});

bind('refresh', refresh);
bind('logout', async () => {
    const form = new FormData();
    form.append(csrf.parameterName, csrf.token);
    await fetch('/logout', { method: 'POST', body: form });
    location.href = '/login';
});

window.addEventListener('beforeunload', () => {
    stopLiveScan();
    stream?.getTracks().forEach(track => track.stop());
});
run(async () => { csrf = await api('/csrf'); await refresh(); });
