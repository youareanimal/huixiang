# 私有部署配置

公开源码不保存实际更新主机。GitHub 构建从现有 PI_SSH_HOST Secret 注入 HUIXIANG_UPDATE_HOST；没有私有配置的开发构建使用不可访问的示例主机。发行构建缺少该 Secret 时停止。

更新服务器本地配置保存于 /etc/huixiang-mirror/private.json，权限应设为仅管理员可读。字段：updateBaseUrl、certificateZone、certificateDomain。证书申请脚本另需通过本地服务环境设置 HUIXIANG_CERT_DOMAIN。OpenResty 示例域名必须在服务器本地替换。不得提交实际值。

现有服务器继续使用已审核且固定哈希的原发布脚本，不自动替换服务器配置。迁移到新脚本前须先准备本地配置，再更新受限发布器；严禁先替换脚本再配置。SSH 仍使用 Secret 和已固定的 known_hosts 严格校验。应用内来源的旧存储标识保留以兼容已有设置。

公开源文件隐藏地址不能让安装包内实际使用的地址保密，也不会删除 Git 历史、既有发布产物或第三方缓存。
