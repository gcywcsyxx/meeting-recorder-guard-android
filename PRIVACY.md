# Privacy / 隐私说明

会议录音守护的设计目标是本地、透明、最少数据。

## 应用处理的数据

- 当前是否处于 Android 通信音频模式；
- 通信音频所属 App 的 UID，用于与内置目标包名列表匹配；
- Zoom 会议界面的前台状态，仅用于在接入音频前开始录制；
- 系统录屏器返回的“正在录制/未录制”状态；
- 最近一次本地运行状态文字。

## 应用不会做的事

- 不申请 Android 联网权限；
- 不上传录像、音频、通知或诊断信息；
- 不进行语音转写或云端分析；
- 不读取聊天记录、联系人或通话对象；
- 不在仓库中保存签名密钥、真机录像或个人日志。

录制文件由手机自带的系统录屏器创建和保存，本应用只负责在检测到通话时调用开始、查询和停止操作。卸载本应用不会删除已有录像。

录制他人前，请确认你有权这样做并取得必要同意。
# Short recordings (0.4.9)

After an app-controlled recording stops, finalized videos with a positive duration below 5 seconds may be moved to system trash. Cleanup is bounded by media IDs observed before starting and at stopping, and restricted to DCIM/ScreenRecorder. Existing historical recordings are not swept. Metadata with unknown/zero duration or pending writes is retained. Only media metadata is inspected; no video contents are read or uploaded. Restore trashed recordings before the system retention period expires.
