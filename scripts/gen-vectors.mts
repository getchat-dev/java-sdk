/**
 * Emits golden signature vectors from the node SDK so the Java port can be
 * verified byte-for-byte against it.
 *
 * It reads the node SDK's source directly from the sibling checkout, so it needs
 * `../node` present with its dependencies installed (`npm i` there once). Run it
 * from `sdk/java`:
 *
 *   ../node/node_modules/.bin/tsx scripts/gen-vectors.ts > src/test/resources/signature-vectors.json
 *   ./gradlew test
 *
 * (tsx comes from the node SDK's devDependencies — borrowing its binary avoids a
 * node_modules in this repo. `npx tsx` works too but downloads a copy.)
 *
 * Set GETCHAT_NODE_SDK to point at a checkout somewhere other than `../node`.
 * The script deliberately does NOT clone the node SDK on its own: the fixture it
 * writes is the trust anchor for signature correctness, so which revision it came
 * from has to be a conscious choice, not whatever HEAD happened to be.
 *
 * A failure in SignatureVectorTest means the two SDKs disagree about the wire
 * format — resolve that before shipping either. Never "just regenerate".
 *
 * Each vector records the exact inputs and the exact URL the node SDK produced.
 * The nonce (and generated session) are random, so the Java test extracts them
 * from `url` and replays them through a deterministic nonce supplier.
 */
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Type-only import: erased before runtime, so it never has to resolve. The real
// module is loaded dynamically below, after the checkout has been verified.
import type { Emby as EmbySdk } from '../../node/src/index.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const nodeSdkDir = process.env.GETCHAT_NODE_SDK
    ? path.resolve(process.env.GETCHAT_NODE_SDK)
    : path.resolve(here, '../../node');

const fail = (problem: string, remedy: string): never => {
    process.stderr.write(
        `gen-vectors: ${problem}\n` +
            `  looked in: ${nodeSdkDir}\n` +
            `  ${remedy}\n` +
            '  or set GETCHAT_NODE_SDK to a node SDK checkout.\n',
    );
    process.exit(1);
};

const entry = path.join(nodeSdkDir, 'src/index.ts');

if (!existsSync(entry)) {
    fail(
        'the node SDK source is not there.',
        'clone it next to this repo:\n' +
            '    git clone https://github.com/getchat-dev/node-sdk.git ../node && (cd ../node && npm i)',
    );
}

if (!existsSync(path.join(nodeSdkDir, 'node_modules'))) {
    fail('the node SDK is present but its dependencies are not installed.', `run: (cd ${nodeSdkDir} && npm i)`);
}

// Record which version produced the fixture — it is the whole point of the file.
const pkg = JSON.parse(readFileSync(path.join(nodeSdkDir, 'package.json'), 'utf8'));
process.stderr.write(`gen-vectors: using ${pkg.name}@${pkg.version} from ${nodeSdkDir}\n`);

const { Emby } = (await import(pathToFileURL(entry).href)) as { Emby: typeof EmbySdk };

const sdk = new Emby({
    id: 'client-42',
    secret: 's3cr3t-key',
    base_url: 'https://chat.example.com/embed',
});

type Vector = { name: string; method: 'url' | 'urlByChatId'; args: unknown; url: string };

const vectors: Vector[] = [];

const url = (name: string, args: Parameters<EmbySdk['url']>[0]) => {
    vectors.push({ name, method: 'url', args, url: sdk.url(args) });
};

const byChatId = (name: string, ...args: Parameters<EmbySdk['urlByChatId']>) => {
    vectors.push({ name, method: 'urlByChatId', args, url: sdk.urlByChatId(...args) });
};

// --- url(): HMAC-SHA256 scheme -------------------------------------------

url('url/minimal', { user: { id: 'u1', name: 'Alice' } });

url('url/no-chat-full-user', {
    user: {
        id: 'u1',
        name: 'Alice Smith',
        email: 'alice@example.com',
        picture: 'https://cdn.example.com/a.png',
    },
});

url('url/with-rights', {
    user: {
        id: 'u1',
        name: 'Alice',
        rights: {
            send_messages: true,
            edit_messages: 'my',
            delete_messages: 'any',
            pin_messages: 'for_everyone',
            react_messages: 'yes',
            send_typing: false,
            // not in the scheme — must be dropped
            bogus_right: true,
        },
    },
});

url('url/rights-colon-enum', {
    user: {
        id: 'u1',
        name: 'Alice',
        rights: { edit_messages: 'my:extra', delete_messages: 'nope' },
    },
});

url('url/chat-string', {
    chat: 'chat-abc',
    user: { id: 'u1', name: 'Alice' },
});

url('url/chat-object', {
    chat: { id: 'chat-abc', title: 'Support', create: true },
    user: { id: 'u1', name: 'Alice' },
});

url('url/chat-create-false', {
    chat: { id: 'chat-abc', title: 'Support', create: false },
    user: { id: 'u1', name: 'Alice' },
});

url('url/participants', {
    chat: { id: 'chat-abc', title: 'Support' },
    user: { id: 'u1', name: 'Alice' },
    participants: [
        { id: 'p1', name: 'Bob' },
        { id: 'p2', name: 'Carol', is_bot: true },
    ],
});

url('url/extra', {
    chat: 'chat-abc',
    user: { id: 'u1', name: 'Alice' },
    extra: { theme: 'dark', lang: 'en', debug: true },
});

url('url/session-no-id', {
    user: { name: 'Anonymous' },
});

url('url/session-explicit', {
    user: { name: 'Anonymous', session: 'fixed-session-token' },
});

url('url/special-chars', {
    chat: { id: 'chat abc', title: 'Q&A / Support = fun' },
    user: { id: 'u 1', name: 'Алиса Иванова', email: 'a+b@example.com' },
});

url('url/numeric-ids', {
    chat: { id: 'chat-abc' },
    user: { id: '10', name: '42' },
});

url('url/trims-whitespace', {
    user: { id: '  u1  ', name: '  Alice  ' },
});

// --- urlByChatId(): legacy MD5 scheme -------------------------------------

byChatId('legacy/chat-string', 'chat-abc', { id: 'u1', name: 'Alice' });

byChatId('legacy/chat-object', { id: 'chat-abc', title: 'Support' }, { id: 'u1', name: 'Alice' });

byChatId('legacy/create-true', { id: 'chat-abc', title: 'Support', create: true }, { id: 'u1', name: 'Alice' });

byChatId('legacy/full-user', 'chat-abc', {
    id: 'u1',
    name: 'Alice',
    email: 'alice@example.com',
    picture: 'https://cdn.example.com/a.png',
    // `link` is in the URL but NOT in the legacy signature whitelist
    link: 'https://example.com/u1',
});

byChatId('legacy/with-rights', 'chat-abc', {
    id: 'u1',
    name: 'Alice',
    rights: { send_messages: true, edit_messages: 'my' },
});

byChatId(
    'legacy/participants',
    'chat-abc',
    { id: 'u1', name: 'Alice' },
    [
        { id: 'p1', name: 'Bob', email: 'bob@example.com' },
        { id: 'p2', name: 'Carol', picture: 'https://cdn.example.com/c.png', is_bot: true },
    ],
);

byChatId('legacy/extra', 'chat-abc', { id: 'u1', name: 'Alice' }, [], { theme: 'dark', debug: false });

byChatId('legacy/session-no-id', 'chat-abc', { name: 'Anonymous' });

byChatId('legacy/special-chars', { id: 'chat abc', title: 'Q&A = fun' }, { id: 'u1', name: 'Алиса' });

process.stdout.write(JSON.stringify(vectors, null, 2));
