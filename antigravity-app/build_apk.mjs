import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const PROJECT_DIR = path.dirname(__filename);

function findAndroidSdk() {
  const candidates = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    path.join(os.homedir(), 'AppData', 'Local', 'Android', 'Sdk'),
    path.join(os.homedir(), 'Library', 'Android', 'sdk'),
    path.join(os.homedir(), 'Android', 'Sdk'),
  ].filter(Boolean);

  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return candidates[0] || path.join(os.homedir(), 'AppData', 'Local', 'Android', 'Sdk');
}

const ANDROID_SDK = findAndroidSdk();

function findBuildToolsDir(sdkDir) {
  const btDir = path.join(sdkDir, 'build-tools');
  if (!fs.existsSync(btDir)) return null;
  const versions = fs.readdirSync(btDir).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  return versions.length > 0 ? path.join(btDir, versions[0]) : null;
}

const BUILD_TOOLS = findBuildToolsDir(ANDROID_SDK) || path.join(ANDROID_SDK, 'build-tools', '34.0.0');

function findAndroidJar(sdkDir) {
  const platDir = path.join(sdkDir, 'platforms');
  if (!fs.existsSync(platDir)) return null;
  const plats = fs.readdirSync(platDir).filter(p => fs.existsSync(path.join(platDir, p, 'android.jar'))).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  return plats.length > 0 ? path.join(platDir, plats[0], 'android.jar') : null;
}

const ANDROID_JAR = findAndroidJar(ANDROID_SDK) || path.join(ANDROID_SDK, 'platforms', 'android-34', 'android.jar');

function findJavaTool(toolName) {
  if (process.env.JAVA_HOME) {
    const candidate = path.join(process.env.JAVA_HOME, 'bin', os.platform() === 'win32' ? `${toolName}.exe` : toolName);
    if (fs.existsSync(candidate)) return candidate;
  }
  try {
    const whichCmd = os.platform() === 'win32' ? `where.exe ${toolName}` : `which ${toolName}`;
    const out = execSync(whichCmd, { stdio: 'pipe' }).toString().trim().split(/\r?\n/)[0];
    if (out && fs.existsSync(out)) return out;
  } catch {}
  return toolName;
}

const JAVAC = findJavaTool('javac');
const JAVA_EXE = findJavaTool('java');
const JAR_EXE = findJavaTool('jar');
const KEYSTORE = path.join(os.homedir(), '.android', 'debug.keystore');

const ext = os.platform() === 'win32' ? '.exe' : '';
const AAPT2 = path.join(BUILD_TOOLS, `aapt2${ext}`);
const D8_JAR = path.join(BUILD_TOOLS, 'lib', 'd8.jar');
const ZIPALIGN = path.join(BUILD_TOOLS, `zipalign${ext}`);
const APKSIGNER_JAR = path.join(BUILD_TOOLS, 'lib', 'apksigner.jar');

function run(cmd, cwd = PROJECT_DIR) {
  console.log(`\n>> Executing: ${cmd}`);
  const out = execSync(cmd, { cwd, stdio: 'pipe' });
  return out.toString();
}

async function build() {
  console.log('='.repeat(70));
  console.log('   ANTIGRAVITY NATIVE APK COMPILATION PIPELINE');
  console.log('='.repeat(70));

  const binDir = path.join(PROJECT_DIR, 'bin');
  const genDir = path.join(PROJECT_DIR, 'gen');
  const objDir = path.join(PROJECT_DIR, 'obj');

  if (fs.existsSync(binDir)) fs.rmSync(binDir, { recursive: true, force: true });
  if (fs.existsSync(genDir)) fs.rmSync(genDir, { recursive: true, force: true });
  if (fs.existsSync(objDir)) fs.rmSync(objDir, { recursive: true, force: true });

  fs.mkdirSync(binDir, { recursive: true });
  fs.mkdirSync(genDir, { recursive: true });
  fs.mkdirSync(objDir, { recursive: true });

  // 1. Compile Resources with AAPT2
  console.log('[*] [Step 1/6] Compiling resources with aapt2...');
  const compiledResZip = path.join(binDir, 'compiled_res.zip');
  run(`"${AAPT2}" compile --dir res -o "${compiledResZip}"`);
  console.log('[✓] Resources compiled successfully.');

  // 2. Link Resources & Generate R.java
  console.log('[*] [Step 2/6] Linking resources & generating R.java...');
  const unalignedApk = path.join(binDir, 'unaligned.apk');
  run(`"${AAPT2}" link -I "${ANDROID_JAR}" "${compiledResZip}" --manifest AndroidManifest.xml --java "${genDir}" -o "${unalignedApk}" --auto-add-overlay`);
  console.log('[✓] Resource linking complete. Generated R.java');

  // 3. Compile Java Source Code with JDK 21 javac
  console.log('[*] [Step 3/6] Compiling Java source files with javac...');
  function getAllFiles(dir, ext = '.java') {
    let results = [];
    for (const f of fs.readdirSync(dir)) {
      const full = path.join(dir, f);
      if (fs.statSync(full).isDirectory()) {
        results = results.concat(getAllFiles(full, ext));
      } else if (f.endsWith(ext)) {
        results.push(full);
      }
    }
    return results;
  }

  const javaFiles = [...getAllFiles(path.join(PROJECT_DIR, 'src')), ...getAllFiles(genDir)];
  const sourceListFile = path.join(binDir, 'sources.txt');
  fs.writeFileSync(sourceListFile, javaFiles.map(f => `"${f.replace(/\\/g, '/')}"`).join('\n'), 'utf8');

  run(`"${JAVAC}" --release 8 -cp "${ANDROID_JAR}" -d "${objDir}" @"${sourceListFile}"`);
  console.log(`[✓] Compiled ${javaFiles.length} Java source files.`);

  // 4. Dexing with D8
  console.log('[*] [Step 4/6] Converting bytecode to DEX using d8...');
  const classFiles = getAllFiles(objDir, '.class');
  const classListFile = path.join(binDir, 'classes.txt');
  fs.writeFileSync(classListFile, classFiles.map(f => f.replace(/\\/g, '/')).join('\n'), 'utf8');

  run(`"${JAVA_EXE}" -cp "${D8_JAR}" com.android.tools.r8.D8 --release --min-api 21 --lib "${ANDROID_JAR}" --output "${binDir}" @"${classListFile}"`);
  console.log('[✓] Generated classes.dex.');

  // 5. Package classes.dex into APK using 7z or powershell / jar / aapt
  console.log('[*] [Step 5/6] Adding classes.dex into APK...');
  run(`"${JAR_EXE}" -uf "${unalignedApk}" -C "${binDir}" classes.dex`);
  console.log('[✓] classes.dex injected into APK package.');

  // 6. Zipalign & Sign
  console.log('[*] [Step 6/6] Aligning & signing APK...');
  const alignedApk = path.join(binDir, 'antigravity-aligned.apk');
  const finalApk = path.join(PROJECT_DIR, 'antigravity-mobile.apk');

  run(`"${ZIPALIGN}" -f -p 4 "${unalignedApk}" "${alignedApk}"`);

  run(`"${JAVA_EXE}" -jar "${APKSIGNER_JAR}" sign --ks "${KEYSTORE}" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out "${finalApk}" "${alignedApk}"`);

  console.log('\n' + '='.repeat(70));
  console.log(`[★] SUCCESS! Native APK created at:\n    ${finalApk}`);
  console.log('='.repeat(70));

  // Verify signature
  const verifyOut = run(`"${JAVA_EXE}" -jar "${APKSIGNER_JAR}" verify --verbose "${finalApk}"`);
  console.log(verifyOut);
}

build().catch(err => {
  console.error('[!] Build Failed:\n', err.message);
  if (err.stdout) console.error('STDOUT:', err.stdout.toString());
  if (err.stderr) console.error('STDERR:', err.stderr.toString());
  process.exit(1);
});
