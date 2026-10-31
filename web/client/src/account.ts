// myTeamSpeak 账号的前端小工具：错误码翻译，以及把选中的图片压成 myTeamSpeak 接受的 PNG。
//
// 头像字节不走 WebSocket（一帧 64 KB，PNG 最大 512 KB），而是 POST /account/avatar，凭账号消息里的
// fileToken 认会话。登录、书签、好友都走 WebSocket，见 account 消息。

const AVATAR_MAX_BYTES = 512 * 1024;
const AVATAR_SIDES = [320, 256, 192, 128];

/** 账号、书签、好友的错误码，翻成人话。 */
export function accountErrorText(code: string | null): string {
  if (!code) return '';
  switch (code) {
    case 'INPUT_REQUIRED':
      return '请填写邮箱和密码';
    case 'INPUT_INVALID':
      return '邮箱、密码或验证码太长';
    case 'MYTS_202':
      return '邮箱或密码不正确';
    case 'MYTS_208':
      return '这个账号开启了两步验证，请填写验证码后重试';
    case 'MYTS_209':
      return '两步验证码不正确，请重试';
    case 'MYTS_103':
      return '账号会话已过期，请重新登录';
    case 'MYTS_UNAVAILABLE':
      return 'myTeamSpeak 暂时连不上，稍后再试';
    case 'NETWORK':
      return '连不上 myTeamSpeak，检查网关的网络后再试';
    case 'MYTS_NO_SESSION':
      return 'myTeamSpeak 没有返回会话，稍后再试';
    case 'BOOKMARK_KEY_REQUIRED':
    case 'BOOKMARK_KEY_MISSING':
      return '账号已登录，但登录接口没有返回书签密钥，书签暂时无法解密';
    case 'BOOKMARK_KEY_AUTH':
      return '书签密钥校验失败，云书签无法解密';
    case 'BOOKMARK_KDF_UNAVAILABLE':
    case 'BOOKMARK_CRYPTO_UNAVAILABLE':
      return '网关的加密服务无法解锁书签';
    case 'BOOKMARK_CHECKSUM':
      return '云书签完整性校验失败';
    default:
      if (code.startsWith('BOOKMARK_SYNC_102')) return '账号会话已过期，请重新登录以刷新书签';
      if (code.startsWith('BOOKMARK_')) return `云书签暂时没同步成功（${code}）`;
      if (code.startsWith('CONTACTS_')) return `好友列表暂时拉不到（${code}）`;
      if (code.startsWith('MYTS_')) return `myTeamSpeak 返回错误（${code}）`;
      return code;
  }
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('图片无法读取'));
    img.src = url;
  });
}

/** 等比裁成正方形，用浏览器的 PNG 编码器压到 [side]×[side]。 */
async function squarePng(file: File, side: number): Promise<Blob> {
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    const canvas = document.createElement('canvas');
    canvas.width = side;
    canvas.height = side;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('浏览器无法处理这张图片');
    const scale = Math.max(side / img.naturalWidth, side / img.naturalHeight);
    const w = img.naturalWidth * scale;
    const h = img.naturalHeight * scale;
    ctx.drawImage(img, (side - w) / 2, (side - h) / 2, w, h);
    return await new Promise((resolve, reject) => {
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('图片编码失败'))), 'image/png');
    });
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * 把一张图片压成最多 320×320、512 KB 的 PNG。先试 320，仍太大就逐级缩小。
 * 返回 null 表示成功，否则是一句给用户看的话。
 */
export async function uploadAccountAvatar(file: File, token: string): Promise<string | null> {
  if (!token) return '账号还没准备好，稍后再试';
  if (!file.type.startsWith('image/')) return '请选择图片文件';
  if (file.size > 20 * 1024 * 1024) return '图片太大，请选 20 MB 以内的图片';
  let png: Blob | null = null;
  try {
    for (const side of AVATAR_SIDES) {
      png = await squarePng(file, side);
      if (png.size <= AVATAR_MAX_BYTES) break;
    }
  } catch (e) {
    return e instanceof Error ? e.message : '图片无法读取';
  }
  if (!png || png.size > AVATAR_MAX_BYTES) return '图片压到 512 KB 仍然太大，换一张简单的图';
  try {
    const res = await fetch('/account/avatar', {
      method: 'POST',
      headers: { 'Content-Type': 'image/png', 'X-File-Token': token },
      body: png,
    });
    if (res.ok) return null;
    return (await res.text().catch(() => '')).trim() || `头像上传失败（HTTP ${res.status}）`;
  } catch {
    return '头像上传失败，检查网络后再试';
  }
}

export async function removeAccountAvatar(token: string): Promise<string | null> {
  if (!token) return '账号还没准备好，稍后再试';
  try {
    const res = await fetch('/account/avatar', {
      method: 'DELETE',
      headers: { 'X-File-Token': token },
    });
    if (res.ok || res.status === 204) return null;
    return (await res.text().catch(() => '')).trim() || `删除头像失败（HTTP ${res.status}）`;
  } catch {
    return '删除头像失败，检查网络后再试';
  }
}
