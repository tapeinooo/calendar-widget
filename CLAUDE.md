# たぺいの予定（calendar-widget）

今日からの1週間の予定（端末のカレンダー＋Google タスク）を1行に並べる自分用のホーム画面ウィジェット。アプリを開くと設定画面。

## 共通ルール

このアプリは自分用アプリの共通ルールに従う：
https://github.com/tapeinooo/app-rules/blob/main/RULES.md

作業を始める前に読むこと（読めないときは `add_repo` で `tapeinooo/app-rules` を足すか、
https://raw.githubusercontent.com/tapeinooo/app-rules/main/RULES.md を開く）。
主な点：GitHub Actions でビルド・main への push で自動リリース・版の番号は自動・同じ署名の鍵・アプリの中の更新・
コミットの作者は tapeinooo の noreply・秘密はリポジトリに入れない・利用者への説明は日本語でやさしく。

## このアプリだけの決まり

- 【例外】署名の鍵は Mac の debug 鍵（app/debug.keystore、パスワード android、別名 androiddebugkey）。Google タスクの読み取りは、この鍵の指紋で Google Cloud に登録してあるので、鍵を変えない。
- 【例外】版の番号：手で付けていた頃（v1.26・versionCode 27）より大きくするため、gradle.properties の versionOffset=100 と versionMajor=2 を使う（versionName は 2.0.<実行番号>）。
- 【移行中】v1.26 までのアプリは apk ブランチの APK を見て更新するので、build.yml がリリースのたびに最新の APK を apk ブランチにも置く。みんな v2 になったら、この手順と apk ブランチは消してよい。
- アプリの更新は Updater.kt（リリースの APK を先にダウンロードしておき、「更新する」で PackageInstaller で入れる。新しい版は通知でも知らせる）。
