const https = require('https');
const PAT = process.argv[2];
const req = https.request({
    hostname: 'api.githubcopilot.com',
    path: '/chat/completions',
    method: 'POST',
    headers: {
        'Authorization': 'Bearer ' + PAT,
        'Content-Type': 'application/json',
        'Copilot-Integration-Id': 'copilot-developer-cli',
        'Editor-Version': 'vscode/1.104.1'
    }
}, (res) => {
    let d = '';
    res.on('data', c => d += c);
    res.on('end', () => console.log('Status:', res.statusCode, '\n', d.slice(0, 200)));
});
req.on('error', e => console.error('Error:', e.message));
req.write(JSON.stringify({ model: 'gpt-4o', messages: [{ role: 'user', content: 'Reply with OK' }] }));
req.end();
