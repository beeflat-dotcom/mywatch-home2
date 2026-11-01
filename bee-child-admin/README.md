# びーふらっと 利用者管理API / NECチャット操作ルート

更新日: 2026-11-01

## 運用ルール

- 利用者の新規登録・基本情報変更は **正式JSONへのAPI操作** に統一する。ExcelからJSONへ書き戻さない。
- 指定がない限り、`child_id`、`client_action_id`、`usage_request_no` など既存の変数名・JSONキー名・固定値を変えない。
- 氏名: `surname` は苗字のみ、`child_given_name` は児童の下の名前のみ、`guardian_given_name` は保護者の下の名前のみ。
- 店舗入力は `shop` = `今` / `福`（保存先JSONでは `今` / `福成`）。`burden_rate` は `0` または `0.1`。
- 既存児童を変更する際は **氏名のみではなく固定`child_id`で特定** する。候補が複数ある場合、勝手に登録・変更しない。
- 利用予定、送迎、昼食などの日別確定情報は変更しない。10月以前の利用記録も変更しない。
- 二重登録を防ぐため`client_action_id`を再試行時に必ず維持する。
- **実データの新規登録/変更は、この会話で明示された具体的な児童情報・操作の指示があるときだけ** 実行する。

## スマホ v2.9.29

- 接続画面で「外部接続：認証済み（Tailscale不要）」になっている必要がある。
- 「個別」画面の「＋新規」「利用者情報」から操作。
- 新規・変更APIは、スマホの認証済みHTTPSゲートウェイ経由 `/monitor/child-admin/*` → NEC内部 `/child-admin/*` へ通信する。
- 8桁コードでペアリングした端末にはトークンが発行される。その他の業務API公開経路を認可するものではない。
- 旧 `8769` 直通では、利用者管理APIは管理認証なしの操作を拒否する。

## ChatGPT → NEC操作

NEC管理のPowerShellツールを呼び、以下のローカルスクリプトから**同じAPI**を実行する。

`%LOCALAPPDATA%\BeeChildAdminBridge\BeeChildAdmin_ChatBridge.ps1`

準備されている操作（通常は`powershell.exe -NoProfile -ExecutionPolicy Bypass -File`で呼び出す）:

- `-Action options` : 次の利用希望Noと登録可能な店舗などを確認（読取のみ）
- `-Action search -Query "<氏名/略称>"` : 既存児童を検索（読取のみ）
- `-Action get -ChildId "child_..."` : 児童詳細を参照（読取のみ）
- `-Action create -PayloadFile "<jsonファイル>"` : 作成内容の確認（書込なし）
- `-Action update -PayloadFile "<jsonファイル>"` : 変更内容の確認（書込なし）
- 上記作成/変更で **`-Apply`** を付けるとAPIへの本登録が行われる。`-DeletePayload` は入力用一時ファイルを削除する。

API例（新規）:

```json
{
  "client_action_id": "UUIDを1個発行し再試行時には同じ値を使用",
  "surname": "苗字",
  "child_given_name": "下の名前",
  "guardian_given_name": "保護者の下の名前",
  "shop": "今",
  "active": true,
  "burden_rate": 0.1,
  "source": "chatgpt"
}
```

変更時は上記に正式`child_id`を加え、`surname`と`child_given_name`を必ず指定する。変更しない項目はそのまま残し、`guardian_given_name`が空文字の場合は既存値を保存する。必要なら`address`, `phone`, `school_name`, `recipient_number`, `school_address`, `day_service_days`, `daytime_days`, `postal_code`, `building_name`, `birth_date`も指定可能。

## サーバーと安全性

- 本体: `Dropbox\AI用\ローカルAI\local_ai_monitor_ver2_server\bee_child_admin_api.py`。既存`main.py`の最後から`install_child_admin_api`で登録。
- ゲートウェイ: `%LOCALAPPDATA%\BeeMonitorGateway\bee_monitor_gateway.py`（127.0.0.1:8788）。
- サーバー: 127.0.0.1:8769 経由でNEC内実行。実際の待受は `0.0.0.0:8769` のため、`/child-admin/*` には別の管理認証キーを要求する。
- 管理認証キーは **NEC内ローカルのみ**: `%LOCALAPPDATA%\BeeMonitorGateway\child_admin_secret.txt`。値はチャット、GitHub、Dropboxには保存・表示しない。
- ゲートウェイはペアリング済みのBearer認証を通した場合だけ管理キーを付与。未認証は401、偽装した非対応経路は404。紛失した端末はペアリング解除する。
- 変更は `利用希望JSON\更新.lock` と既存の基本情報ロックで排他。入力内容・ID重複・再実行キーを検証。
- 更新前JSONは `児童登録変更バックアップ\<日時>_<action_id>` に保存。履歴は `児童登録変更履歴.json`。
- 月次一覧は当月と将来月の正式JSONのみ更新し、`運用.json` の`master_month`を当月にする。過去月JSONと確定済み利用条件は維持。

## Excel住所録

- 出力: `Dropbox\BU\利用希望\2026年\簡易住所録.xlsx`
- 先頭「最新住所録（自動）」を新規生成（元の9シートは維持）。
- 変更前ファイル: `簡易住所録_バックアップ` に日時付きで保存。
- タスク `Bee-SimpleAddressBook-AutoExport` が3分おきにマスタ更新を検知。利用者変更APIは更新後にタスク起動も要求する。
- 住所録の利用中判定は`児童利用状態.json`の明示的な状態を優先する。2026-11-01の初期値は82名、うち利用中81名。
- Excelを編集してもマスタに反映されない。Excelは人間の閲覧用だけ。

## テスト・留意事項

- 合成データの新規登録・更新・重複防止・復旧用バックアップ・HTTP認証のテストは成功。
- 実PCで未認証403、ペア済み端末HTTPS経路200、未認証401、無効な書込400、取り消したトークン401を確認。
- **本物の児童の新規登録・変更はまだ行っていない**。最初の実運用時には住所録更新と当月JSON/基本情報JSON/状態JSON/履歴を照合する。
- 旧Cloudflare直接公開経路のその他のAPIは、この変更だけでは保護されない。PTTなど既存ルートは維持しており、ネットワーク全体の安全性確認は別途必要。
- 当月の正式`YYYY-MM.json`が存在しない場合は勝手に作成せず503で停止。年度切替や新月の準備は既存処理で行う。
- ロールバック: NEC内`main.py.backup_child_admin_*`、`bee_monitor_gateway.py.backup_admin_*` を参照。

GitHub保守ソース: `beeflat-dotcom/mywatch-home2` の `bee-child-admin/`。
