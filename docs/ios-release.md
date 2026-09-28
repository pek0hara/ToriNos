# iOS リリース手順

Fastlane を使い、コマンドから Release ビルドを作成して TestFlight へアップロードする。

## 初回準備

Ruby 依存関係をインストールする。

```sh
bundle install
```

App Store Connect の API キーを作成し、次の環境変数を設定する。秘密鍵（`.p8`）はリポジトリに追加しない。

```sh
export APP_STORE_CONNECT_API_KEY_KEY_ID="キーID"
export APP_STORE_CONNECT_API_KEY_ISSUER_ID="Issuer ID"
export APP_STORE_CONNECT_API_KEY_KEY_FILEPATH="/絶対パス/AuthKey_XXXXXXXXXX.p8"
```

Xcode の Accounts に Apple ID を追加し、`2F7KXC2828` チームの署名証明書を利用できる状態にしておく。Fastlane は必要な Provisioning Profile の更新を Xcode に許可してビルドする。

API キーを使わない場合は、代わりに `APP_STORE_CONNECT_USERNAME` または `FASTLANE_USER` を設定する。

## TestFlight へアップロード

バージョン番号とビルド番号を更新してコミットした後、作業ツリーが空の状態で次を実行する。

```sh
bundle exec fastlane ios release
```

処理完了を待ってから終了したい場合は、次を実行する。

```sh
bundle exec fastlane ios release wait_for_processing:true
```

IPA は `build/ios/release/ToriNos.ipa` に出力される。通常は変更が残っている状態での誤リリースを防止する。意図的に未コミットの変更を含める場合のみ `allow_dirty:true` を指定する。

```sh
bundle exec fastlane ios release allow_dirty:true
```

このレーンは TestFlight へのアップロードまでを行い、外部テスターへの配布や App Store 審査提出は自動では行わない。
