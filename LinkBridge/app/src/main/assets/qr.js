(function () {
    'use strict';

    // ---- Config (must match the Android app's WebRtcClient exactly) ----
    const ICE_SERVERS = [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' },
        {
            urls: 'turn:openrelay.metered.ca:80',
            username: 'openrelayproject',
            credential: 'openrelayproject'
        },
        {
            urls: 'turn:openrelay.metered.ca:443',
            username: 'openrelayproject',
            credential: 'openrelayproject'
        },
        {
            urls: 'turn:openrelay.metered.ca:443?transport=tcp',
            username: 'openrelayproject',
            credential: 'openrelayproject'
        }
    ];
    const CHUNK_SIZE = 16 * 1024;
    const MAX_BUFFERED_BYTES = 1 * 1024 * 1024;
    const SIGNALING_URL_KEY = 'linkbridge_signaling_url';

    // ---- Elements ----
    const el = (id) => document.getElementById(id);
    const setupBlock = el('setup-block');
    const showBlock = el('show-block');
    const statusBlock = el('status-block');
    const verifyWaitBlock = el('verify-wait-block');
    const transferBlock = el('transfer-block');
    const statusText = el('status-text');
    const myCodeEl = el('my-code');
    const fileInput = el('file-input');
    const progressContainer = el('transfer-progress-container');

    // ---- State ----
    let ws = null;
    let pc = null;
    let dataChannel = null;
    let sessionId = null;
    let myCode = null;
    let pendingSendFile = null;
    let receivingMeta = null; // {name, size, receivedChunks: [], bytesSoFar}

    function showOnly(block) {
        [setupBlock, showBlock, statusBlock, verifyWaitBlock, transferBlock].forEach((b) => {
            b.style.display = b === block ? 'block' : 'none';
        });
    }

    function setStatus(text) {
        statusText.textContent = text;
        showOnly(statusBlock);
    }

    // ---- Step 0: signaling server address ----
    function getSignalingUrl() {
        return localStorage.getItem(SIGNALING_URL_KEY);
    }

    el('btn-save-signaling').addEventListener('click', () => {
        const url = el('signaling-url-input').value.trim();
        if (!url.startsWith('ws://') && !url.startsWith('wss://')) {
            alert('Enter a ws:// or wss:// address.');
            return;
        }
        localStorage.setItem(SIGNALING_URL_KEY, url);
        startShowFlow();
    });

    function init() {
        if (getSignalingUrl()) {
            startShowFlow();
        } else {
            showOnly(setupBlock);
        }
    }

    // ---- Step 1: generate session + code, show QR, connect signaling ----
    function startShowFlow() {
        const signalingUrl = getSignalingUrl();
        sessionId = crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random();
        myCode = String(100000 + Math.floor(Math.random() * 900000));

        const payload = 'linkbridge://pair?session=' + sessionId +
            '&signal=' + encodeURIComponent(signalingUrl);

        el('qrcode-canvas').innerHTML = '';
        // eslint-disable-next-line no-undef
        new QRCode(el('qrcode-canvas'), { text: payload, width: 240, height: 240 });
        myCodeEl.textContent = myCode;
        showOnly(showBlock);

        connectSignaling(signalingUrl, sessionId);
    }

    // ---- Signaling ----
    function connectSignaling(signalingUrl, sessionId) {
        const wsUrl = signalingUrl.replace(/\/$/, '') + '/pair';
        ws = new WebSocket(wsUrl);

        ws.addEventListener('open', () => {
            ws.send(JSON.stringify({ type: 'join', sessionId: sessionId }));
        });

        ws.addEventListener('message', (event) => {
            let message;
            try {
                message = JSON.parse(event.data);
            } catch (e) {
                return;
            }
            handleSignalingMessage(message);
        });

        ws.addEventListener('close', () => {
            if (!dataChannel || dataChannel.readyState !== 'open') {
                setStatus('Signaling connection closed.');
            }
        });

        ws.addEventListener('error', () => {
            setStatus('Could not reach the signaling server.');
        });
    }

    function sendSignal(wireSignal) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'signal', payload: JSON.stringify(wireSignal) }));
        }
    }

    async function handleSignalingMessage(message) {
        switch (message.type) {
            case 'waiting':
                setStatus('Waiting for the other device…');
                showOnly(showBlock); // keep QR visible while waiting
                el('status-block').style.display = 'block'; // show spinner alongside QR
                break;
            case 'paired':
                setStatus('Found the other device — connecting…');
                el('show-block').style.display = 'block';
                createPeerConnection();
                dataChannel = pc.createDataChannel('linkbridge-transfer');
                attachDataChannel(dataChannel);
                {
                    const offer = await pc.createOffer();
                    await pc.setLocalDescription(offer);
                    sendSignal({ kind: 'sdp-offer', sdp: offer.sdp });
                }
                break;
            case 'signal': {
                const wire = JSON.parse(message.payload);
                if (wire.kind === 'sdp-answer') {
                    await pc.setRemoteDescription({ type: 'answer', sdp: wire.sdp });
                } else if (wire.kind === 'ice-candidate' && wire.candidate) {
                    await pc.addIceCandidate({
                        candidate: wire.candidate,
                        sdpMid: wire.sdpMid,
                        sdpMLineIndex: wire.sdpMLineIndex
                    });
                }
                break;
            }
            case 'peer-left':
                setStatus('The other device disconnected.');
                break;
            case 'error':
                setStatus('Error: ' + message.message);
                break;
        }
    }

    // ---- WebRTC ----
    function createPeerConnection() {
        pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
        pc.addEventListener('icecandidate', (event) => {
            if (event.candidate) {
                sendSignal({
                    kind: 'ice-candidate',
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex
                });
            }
        });
    }

    function attachDataChannel(channel) {
        channel.binaryType = 'arraybuffer';
        channel.addEventListener('open', () => {
            // Web is always the QR "shower": the code is already on screen, just wait
            // for the scanning device to send back what they typed.
            showOnly(verifyWaitBlock);
        });
        channel.addEventListener('close', () => setStatus('The other device disconnected.'));
        channel.addEventListener('message', (event) => {
            if (typeof event.data === 'string') {
                handleControlMessage(event.data);
            } else {
                handleIncomingChunk(event.data);
            }
        });
    }

    function sendControl(obj) {
        if (dataChannel && dataChannel.readyState === 'open') {
            dataChannel.send(JSON.stringify(obj));
        }
    }

    // ---- Verification + file protocol (mirrors ControlMessage on the Android side) ----
    function handleControlMessage(text) {
        let message;
        try {
            message = JSON.parse(text);
        } catch (e) {
            return;
        }
        switch (message.type) {
            case 'verify-code': {
                const ok = message.code === myCode;
                sendControl({ type: 'verify-result', ok: ok });
                if (ok) showReady();
                break;
            }
            case 'file-offer':
                if (confirm('The other device wants to send "' + message.fileName +
                    '" (' + formatBytes(message.fileSize) + '). Accept?')) {
                    receivingMeta = { name: message.fileName, size: message.fileSize, chunks: [], bytesSoFar: 0 };
                    sendControl({ type: 'file-accept' });
                } else {
                    sendControl({ type: 'file-decline' });
                }
                break;
            case 'file-accept':
                sendFileData();
                break;
            case 'file-decline':
                pendingSendFile = null;
                addProgressLine('Declined by the other device.', 100);
                break;
            case 'file-complete':
                finalizeReceivedFile();
                break;
        }
    }

    function showReady() {
        showOnly(transferBlock);
    }

    el('btn-pick-file').addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', () => {
        const file = fileInput.files[0];
        if (!file) return;
        pendingSendFile = file;
        sendControl({ type: 'file-offer', fileName: file.name, fileSize: file.size });
        addProgressLine('Sending ' + file.name + '…', 0);
    });

    function readSlice(file, start, end) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(new Uint8Array(reader.result));
            reader.onerror = () => reject(reader.error);
            reader.readAsArrayBuffer(file.slice(start, end));
        });
    }

    async function sendFileData() {
        const file = pendingSendFile;
        if (!file) return;
        let sent = 0;
        try {
            while (sent < file.size) {
                const end = Math.min(sent + CHUNK_SIZE, file.size);
                const chunk = await readSlice(file, sent, end);

                while (dataChannel.bufferedAmount > MAX_BUFFERED_BYTES) {
                    await new Promise((r) => setTimeout(r, 20));
                }
                dataChannel.send(chunk);
                sent = end;
                updateProgressLine(Math.round((sent / file.size) * 100));
            }
            sendControl({ type: 'file-complete' });
            updateProgressLine(100, true);
        } catch (e) {
            console.error('Send failed:', e);
            addProgressLine('Send failed.', 0);
        } finally {
            pendingSendFile = null;
        }
    }

    function handleIncomingChunk(data) {
        if (!receivingMeta) return;
        const chunkPromise = data instanceof Blob ? data.arrayBuffer() : Promise.resolve(data);
        chunkPromise.then((buf) => {
            receivingMeta.chunks.push(new Uint8Array(buf));
            receivingMeta.bytesSoFar += buf.byteLength;
            const percent = receivingMeta.size > 0
                ? Math.round((receivingMeta.bytesSoFar / receivingMeta.size) * 100) : 0;
            updateProgressLine(percent);
        });
    }

    function finalizeReceivedFile() {
        if (!receivingMeta) return;
        const blob = new Blob(receivingMeta.chunks);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = receivingMeta.name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 5000);
        updateProgressLine(100, true);
        receivingMeta = null;
    }

    // ---- Small progress UI helper ----
    let currentProgressRow = null;
    function addProgressLine(label, percent) {
        const row = document.createElement('div');
        row.className = 'progress-row';
        row.innerHTML = '<span class="progress-label">' + label + '</span>' +
            '<div class="progress-track"><div class="progress-fill" style="width:' + percent + '%"></div></div>';
        progressContainer.prepend(row);
        currentProgressRow = row;
    }
    function updateProgressLine(percent, done) {
        if (!currentProgressRow) addProgressLine('Transferring…', percent);
        const fill = currentProgressRow.querySelector('.progress-fill');
        if (fill) fill.style.width = percent + '%';
        if (done) {
            const label = currentProgressRow.querySelector('.progress-label');
            if (label) label.textContent = 'Done';
        }
    }

    function formatBytes(bytes) {
        if (!bytes) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
    }

    init();
})();
