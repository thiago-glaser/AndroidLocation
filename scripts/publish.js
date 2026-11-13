/**
 * Google Play Developer Publishing API client for LocationServices.
 * Uses pure Node.js standard libraries (crypto, https, fs, path) - zero external dependencies.
 */

const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const path = require('path');

function getArg(flag, defaultValue = null) {
    const idx = process.argv.indexOf(flag);
    if (idx !== -1 && idx + 1 < process.argv.length) {
        return process.argv[idx + 1];
    }
    return defaultValue;
}

const keyPath = getArg('--key', path.resolve(__dirname, '../play-service-account.json'));
const aabPath = getArg('--aab', path.resolve(__dirname, '../app/build/outputs/bundle/release/app-release.aab'));
const packageName = getArg('--package', 'com.deliverytrips.locationservice');
const trackName = getArg('--track', 'internal');
const releaseStatus = getArg('--status', 'completed');
const releaseNameArg = getArg('--name', null);
const notesEn = getArg('--notes-en', 'Bug fixes, performance improvements, and stability enhancements.');
const notesPt = getArg('--notes-pt', 'Correções de bugs, melhorias de desempenho e otimizações de estabilidade.');

if (!fs.existsSync(keyPath)) {
    console.error(`[FAIL] Service account key file not found at: ${keyPath}`);
    process.exit(1);
}

if (!fs.existsSync(aabPath)) {
    console.error(`[FAIL] Release AAB file not found at: ${aabPath}`);
    process.exit(1);
}

const key = JSON.parse(fs.readFileSync(keyPath, 'utf8'));

function httpRequest(options, data = null, pipeStream = null) {
    return new Promise((resolve, reject) => {
        const req = https.request(options, (res) => {
            let resBody = '';
            res.on('data', chunk => resBody += chunk);
            res.on('end', () => {
                let parsed = null;
                try {
                    parsed = JSON.parse(resBody);
                } catch (_) {
                    parsed = resBody;
                }
                if (res.statusCode >= 200 && res.statusCode < 300) {
                    resolve({ status: res.statusCode, data: parsed, headers: res.headers });
                } else {
                    const err = new Error(`HTTP ${res.statusCode}: ${typeof parsed === 'object' ? JSON.stringify(parsed) : parsed}`);
                    err.status = res.statusCode;
                    err.data = parsed;
                    reject(err);
                }
            });
        });

        req.on('error', reject);

        if (pipeStream) {
            pipeStream.pipe(req);
        } else if (data) {
            req.write(data);
            req.end();
        } else {
            req.end();
        }
    });
}

async function getAccessToken() {
    console.log('    Authenticating with Google OAuth2 using Service Account...');
    const now = Math.floor(Date.now() / 1000);
    const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
    const claimSet = Buffer.from(JSON.stringify({
        iss: key.client_email,
        scope: 'https://www.googleapis.com/auth/androidpublisher',
        aud: key.token_uri,
        exp: now + 3600,
        iat: now
    })).toString('base64url');

    const sign = crypto.createSign('RSA-SHA256');
    sign.update(`${header}.${claimSet}`);
    const signature = sign.sign(key.private_key, 'base64url');
    const jwt = `${header}.${claimSet}.${signature}`;

    const postData = `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`;

    const res = await httpRequest({
        hostname: 'oauth2.googleapis.com',
        path: '/token',
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'Content-Length': Buffer.byteLength(postData)
        }
    }, postData);

    if (!res.data.access_token) {
        throw new Error(`Failed to obtain access token: ${JSON.stringify(res.data)}`);
    }

    return res.data.access_token;
}

async function main() {
    console.log(`[INFO] Package: ${packageName}`);
    console.log(`[INFO] Track: ${trackName}`);
    console.log(`[INFO] AAB: ${aabPath}`);

    const token = await getAccessToken();
    console.log('    [OK] OAuth2 access token acquired.');

    // 1. Create Edit
    console.log('    Creating new edit transaction on Google Play...');
    const editRes = await httpRequest({
        hostname: 'androidpublisher.googleapis.com',
        path: `/androidpublisher/v3/applications/${packageName}/edits`,
        method: 'POST',
        headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Length': '0'
        }
    });

    const editId = editRes.data.id;
    console.log(`    [OK] Edit transaction created (ID: ${editId})`);

    try {
        // 2. Upload Bundle
        const stat = fs.statSync(aabPath);
        const sizeMb = (stat.size / (1024 * 1024)).toFixed(2);
        console.log(`    Uploading App Bundle (${sizeMb} MB) to Google Play...`);

        const fileStream = fs.createReadStream(aabPath);
        const uploadRes = await httpRequest({
            hostname: 'androidpublisher.googleapis.com',
            path: `/upload/androidpublisher/v3/applications/${packageName}/edits/${editId}/bundles?uploadType=media`,
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/octet-stream',
                'Content-Length': stat.size
            }
        }, null, fileStream);

        const versionCode = uploadRes.data.versionCode;
        const sha256 = uploadRes.data.sha256;
        console.log(`    [OK] Bundle uploaded successfully! Version Code: ${versionCode} (SHA256: ${sha256.substring(0, 12)}...)`);

        // 3. Update Track
        const releaseName = releaseNameArg || `${versionCode}`;
        console.log(`    Assigning version ${versionCode} (${releaseName}) to '${trackName}' track...`);

        const releasePayload = {
            track: trackName,
            releases: [
                {
                    name: releaseName,
                    versionCodes: [String(versionCode)],
                    status: releaseStatus,
                    releaseNotes: [
                        { language: 'en-US', text: notesEn },
                        { language: 'pt-BR', text: notesPt }
                    ]
                }
            ]
        };

        const releaseJson = JSON.stringify(releasePayload);
        await httpRequest({
            hostname: 'androidpublisher.googleapis.com',
            path: `/androidpublisher/v3/applications/${packageName}/edits/${editId}/tracks/${trackName}`,
            method: 'PUT',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(releaseJson)
            }
        }, releaseJson);

        console.log(`    [OK] Track '${trackName}' updated with release '${releaseName}'.`);

        // 4. Commit Edit
        console.log('    Committing edit transaction...');
        await httpRequest({
            hostname: 'androidpublisher.googleapis.com',
            path: `/androidpublisher/v3/applications/${packageName}/edits/${editId}:commit`,
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Length': '0'
            }
        });

        console.log(`\n==========================================`);
        console.log(`[SUCCESS] Release ${versionCode} (${releaseName}) published to '${trackName}' track!`);
        console.log(`==========================================`);

    } catch (err) {
        console.error(`\n[FAIL] Publishing error: ${err.message}`);
        console.log(`    Aborting edit transaction ${editId}...`);
        try {
            await httpRequest({
                hostname: 'androidpublisher.googleapis.com',
                path: `/androidpublisher/v3/applications/${packageName}/edits/${editId}`,
                method: 'DELETE',
                headers: { 'Authorization': `Bearer ${token}` }
            });
            console.log('    [OK] Edit transaction cleaned up.');
        } catch (_) {}
        process.exit(1);
    }
}

main().catch(err => {
    console.error(`[FATAL] ${err.message}`);
    process.exit(1);
});
