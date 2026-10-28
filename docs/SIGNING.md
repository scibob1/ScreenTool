# 自签名与发布密钥 / Signing & release keys

## 先说清楚：仓库里现在用的是"公开密钥"
`app/screentool.keystore` 连同它的密码（`screentool`）都写在 `build.gradle` 里，一起进了开源仓库——**谁都能看到这把钥匙**。它的意义只是"每次 CI 打包的 APK 用同一个签名，方便你自己覆盖安装升级"，**不能用它上架应用商店**，也不要用它签任何你在意安全性的版本：知道私钥的人理论上能发布一个冒充你应用更新的 APK。

真正要分发（哪怕只是发到 Releases 给别人下载）时，应该用一把**只有你自己拿得到**的私钥，这份文档说明怎么做，以及怎么借助 GitHub 的 Secrets 让 Actions 能用这把钥匙打包，而不把钥匙本身存进仓库。

## 一、CI Secrets 是什么
"CI Secrets" 不是哪一个具体产品的名字，泛指 **CI/持续集成平台提供的加密变量存储**：你把密码、密钥这类敏感信息存进去，平台只在 workflow 运行时把它们临时解密成环境变量给你的脚本用，日志里出现这些值会被自动打码（显示成 `***`），仓库里任何人都看不到原文，fork 出去的仓库跑 workflow 时默认也拿不到。

在 GitHub 上，这个功能就叫 **"Actions secrets and variables"**——完全是你想用的那个东西，不需要额外的服务。

## 二、生成你自己的私钥

在你自己的电脑上执行（不是在 Codespaces 里，避免私钥经过云端）：

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias screentool \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -storepass "换成你自己的强密码" \
  -keypass "换成你自己的强密码" \
  -dname "CN=你的名字或项目名, O=你, C=CN"
```

会在当前目录生成 `release.keystore`。**这个文件和里面的密码不要提交到 Git**，仓库的 `.gitignore` 已经排除了 `release.keystore`。把它备份到你自己的地方（比如加密的网盘/密码管理器），丢了就无法再用同一个身份更新已发布的 APK。

## 三、把私钥交给 GitHub Actions，但不让它进仓库

1. 把私钥转成一段文本（base64），方便粘贴成一个 Secret：
   - macOS / Linux： `base64 -i release.keystore | pbcopy`（Linux 用 `base64 -w0 release.keystore | xclip -selection clipboard`，没有 `xclip` 就去掉 `| xclip...` 直接看终端输出复制）
   - Windows PowerShell： `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Set-Clipboard`
2. 打开你的仓库 → **Settings → Secrets and variables → Actions** → **New repository secret**，依次新建这 4 个：

   | Secret 名称 | 值 |
   |---|---|
   | `SIGN_STORE_BASE64` | 上一步复制的那一长串 base64 文本 |
   | `SIGN_STORE_PASSWORD` | 你生成密钥时的 `-storepass` |
   | `SIGN_KEY_ALIAS` | 你生成密钥时的 `-alias`（上面示例是 `screentool`） |
   | `SIGN_KEY_PASSWORD` | 你生成密钥时的 `-keypass` |

3. 发布用的工作流文件 `.github/workflows/release.yml` 已经改好（日常编译的 `build.yml` 只用公开密钥，不处理 Secrets）：如果这 4 个 Secret 都配置了，打包时会自动解码出私钥、设置好环境变量，`app/build.gradle` 检测到这些环境变量就会优先用它们签名；**没配置的话会在日志里给出警告并退回仓库自带的公开密钥**，不会导致构建失败。想让“缺私钥就直接失败”，在仓库 Settings → Secrets and variables → Actions → **Variables** 里新建变量 `REQUIRE_PRIVATE_KEY`，值填 `true`。发布说明末尾会写明这个包是用哪把密钥签的。

Secret 和仓库里的**变量（Variables）**不是一回事：Variables 是明文的、给非敏感配置用的；密码、密钥一律用 Secrets，不要用 Variables。

## 四、注意事项
- 换了签名密钥后，新签名的 APK **不能直接覆盖安装**用旧密钥（包括仓库自带的公开密钥）装的版本，系统会提示"签名不一致"，需要先卸载旧版本（会丢失本地设置，除非你先用应用内的备份功能导出）。建议只在正式准备对外发布时切换一次，之后就固定用这一把私钥。
- Secrets 只在你自己仓库里跑的 workflow 能用到；别人 fork 你的仓库后，他们的 Actions 跑不出你的签名版本（这是 GitHub 的安全设计，fork 出去的 PR 默认拿不到上游仓库的 Secrets）。
- 不要把 `storePassword` / `keyPassword` 以明文写进任何 `.yml`、`.gradle` 或其他会提交的文件里，哪怕只是临时调试。
