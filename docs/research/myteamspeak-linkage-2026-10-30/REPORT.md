# myTeamSpeak 语音连接账号关联：只读调查

调查日期：2026-10-30。未修改账号、服务器配置、书签、身份或应用代码；未登录新会话、重连服务器、上传头像或读取聊天正文。未运行编译或测试。输出仅包含静态定义与完全合成样例。

## 版本与证据范围

- 官方 Windows 客户端：6.0.0-beta5。来自本机 `C:/Users/93608/AppData/Local/TeamSpeak/Logs/Default/` 下的官方启动日志；2026-10-29 日志仍记录此版本。EXE 的 ProductVersion 只有 `6, 0, 0, 0`，不能代替 beta 版本。
- 更新服务器 服务器：6.0.0-beta13.1。只读 Docker 镜像为 `teamspeaksystems/teamspeak6-server:6.0.0-beta13.1`，容器 `teamspeak6-server`；启动日志独立确认相同补丁号。
- 官方历史日志包含 `updates.example.invalid` 连接目标。服务器版本是调查时的实际部署版本，不能保证与所有历史连接时的部署相同。
- 本轮没有可用的官方客户端活动进程，未捕获本次实时登录或 UDP 明文命令。下文明确区分静态定义、公开实现和待确认的线上行为。
- 重新从当前安装的 `C:/Users/93608/AppData/Local/Programs/TeamSpeak/TeamSpeak.dll` 提取 protobuf 描述符。DLL SHA-256 与原始字段编号见 `static-schemas.json`；其中没有任何账号值。

## 获取账号关联材料

此版本证据不支持凭空假设一个 `getServerAuthToken` HTTP 接口。

官方 `myteamspeak_login.proto` 的 `LoginService` 有 `login`、`loginWithAuthToken`、`loginWithRenewalToken`，返回 `com.teamspeak.myteamspeak.proto.LoginSession`。静态地址为 `https://clientapi.myteamspeak.com/authentication`。回响已有 RPC 封装采用 `Content-Type: application/ts3cloud`，请求正文为方法名长度 varint、ASCII 方法名、protobuf 请求数据；本轮未重新发起登录来确认官方实时请求。

与语音账号关联直接相关的 LoginSession 字段：

| 字段号 | 字段 | 类型 |
| --- | --- | --- |
| 9 | myts_id_data | MyTeamSpeakIdData |
| 10 | mytsid_user_cert | MyTSUserCertificate |
| 11 | user_avatar | AvatarData |

`MyTeamSpeakIdData`：`user_public_key` #1 bytes、`user_private_key` #2 message、`account_creation_time` #3 uint64、`my_teamspeak_id` #4 bytes、`public_signature` #5 bytes。私钥子消息定义区分 encrypted_user_private_key 与 decrypted_user_private_key；本轮仅导出字段定义，没有读取任何真实私钥。

`MyTSUserCertificate.cert`：#1 bytes。

官方静态前端 `html/client_ui/main.js` 还存在 `GetAccountMyteamspeakId()`，调用 `tsclient_internal.getAccountMyteamspeakId(callback)`。这是本机前端到原生层的桥接接口，**不是已证实的远端 HTTP RPC**。默认连接选项包含 `send_mytsid=true`，书签亦保存 send_mytsid。原生 DLL 存在 send_mytsid_on_server、myteamspeakid_data、myteamspeakid_user_certificate、撤销列表和无效身份数据相关诊断字符串。

## 本机组装的证明结构

官方 `Account_Serializing.proto` 定义 `com.teamspeak.account.proto.MyTSID_Verification_Token`：

| 字段号 | 名称 | 类型 |
| --- | --- | --- |
| 1 | verification_sign | bytes |
| 2 | sign_timestamp | uint64 |
| 3 | user_pub_key | bytes |
| 4 | pub_sign | bytes |
| 5 | pub_sign_cert | bytes |
| 6 | ac_time | uint64 |
| 7 | mytsid | bytes |

这些字段与登录返回的公钥、账号创建时间、账号 ID、公钥签名和用户证书存在明确名称对应；但字段名对应不等于已验证的签名生成算法。结构表明不能只发送邮箱、昵称或账号 UUID 来替代证明。

## 头像与徽章

- 登录返回 `AvatarData`：info #1 AvatarInfo、timestamp #2 uint64、sign #3 bytes、optional #4 google.protobuf.Any。
- AvatarInfo.map #1 repeated AvatarMap；AvatarMap.state #1 AvatarState enum、name #2 string。
- `UserAccountService.getSignedBadges` 输入为 user.Session（session #1 string），输出为 UserBadgesSignedResponse：list #1 UserBadgesSignedList，error_code #2 enum。服务静态地址为 `https://clientapi.myteamspeak.com/user`。
- list.badges #1 repeated SignedUserBadge：badge #1 UserBadge、sign #2 bytes、sign_timestamp #3 uint64。UserBadge 的 uuid/name/description/url 为 string，received 为 int64。
- `getBadges` 与 `getSignedBadges` 是两个不同方法，前者的展示数据不能直接替代后者的签名结果。
- `getAccountData` 返回的 avatarInfo 是头像名称映射；登录返回的 user_avatar 则是带时间戳、签名的 AvatarData，二者不可混用。
- `requestAvatarSignedUrl` 属于头像存储/上传链路，不能据其名称把它当作语音账号关联认证接口。

## 语音命令与字段

官方 DLL 包含 `clientinit`、`clientupdate`、`updatemytsid` 以及 `client_myteamspeak_id`、`client_myteamspeak_avatar`、`client_signed_badges`。

公开 ReSpeak 客户端实现的 OutClientInitPart 在 clientinit 中具有对应的 my_team_speak_id、my_team_speak_avatar、signed_badges 可选项，但其默认值都是 None。这是字段入口参考，**不是已经实现官方账号认证的参考客户端**：

https://docs.rs/tsclientlib/latest/src/tsclientlib/lib.rs.html#538-565

官方静态 `client/integration.proto` 定义 UpdateMyTSIDRequest.myts_id #1 string；ClientSelfSettableProperties 有 myteamspeak_id #14 string、signed_badges #17 string；ClientProperties 有 myteamspeak_id #62 string、myteamspeak_avatar #63 string、signed_badges #64 string。这些是官方程序嵌入的 API 消息定义，不能直接把其 protobuf 字段编号当成回响当前 UDP 文本协议的字段编号。

**尚未确认**：MyTSID_Verification_Token 如何编码进 client_myteamspeak_id，头像和徽章数据具体序列化/转义格式，updatemytsid 的实际文本命令参数名，以及官方此次连接选择初始化发送还是连接后补发。不得把静态名称存在写成已抓到官方线上命令。

回响缺口已在源码定位：`tsproto/src/main/kotlin/io/github/korppujauho/pocketts/proto/TsClient.kt` 的 startEncryption() 构造 clientinit，但没有写入上述三个账号关联字段。ServerModel.kt 只处理服务端下发的 client_myteamspeak_avatar；界面的本机头像覆盖显示不构成向服务器关联账号。

## 有效期与绑定规则：证据边界

- 证明有 sign_timestamp，头像有 timestamp/sign，徽章有 sign_timestamp/sign。
- 官方程序有身份/证书撤销列表、当前身份无效与重新请求身份的处理线索。
- 没有确认固定有效期；不能把 sign_timestamp 当成 expires_in，也不能从账号 session 或 Matrix access_token 的寿命推导语音证明寿命。
- 未确认 verification_sign 的算法、输入字节次序、是否包含语音 identity UID、公钥、服务器 UID、握手随机数或时钟容差。公钥、证书与签名字段提示存在账号密钥认证链，但不能据此宣称具体身份绑定规则。
- 本轮未解析真实私钥、证书载荷或认证令牌来进一步推导这些规则，也未尝试 setNewUserPublicKey 或任何重置/替换身份操作。

## 开发交付与后续验证点

- `static-schemas.json`：当前官方 DLL 提取的字段定义、类型、编号、嵌套结构及二进制指纹。
- `synthetic-fixtures.json`：全部由占位符与零时间戳构造，不来自真实认证响应，不可用于登录；wireEncodingConfirmed=false。
- 可在后续云端测试字段解析、登录材料与 UI 展示数据隔离、缺失证明时不冒充已关联账号、账号切换后丢弃旧证明、时间戳边界及无效签名处理。此处只是测试输入建议，本轮未执行测试。
- 真正实现之前仍需确认原生证明生成调用点及已脱敏的连接命令结构。只记录方法名、字段名、类型、长度或布尔状态，不记录认证值、私钥或完整报文。
- 官方 SDK 字段参考：https://github.com/teamspeak/ts3client-pluginsdk/blob/master/include/teamspeak/public_rare_definitions.h

结论：缺口是账号认证材料获取/保留、原生证明生成与语音连接发送链路，不是单独补一个头像 URL。现有证据足以确定数据结构和缺失位置，尚不足以声称已掌握官方可互操作的签名算法及全部线缆编码。
