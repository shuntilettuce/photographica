# Changelog

## 1.3.0

一枚の絵をぼかすのではなく、**絞りを積分して**被写界深度を得る版。さらに露光時間も一緒に
積分するので、シャッター速度ダイヤルがようやく意味を持つ。加えて、ぼけた前景の「裏側」を、
カメラを動かさずに得る二つ目の答えを載せた。

Depth of field taken by **integrating the aperture** instead of by blurring one frame, and the
exposure integrated alongside it so the shutter dial finally means something — plus a second
answer to what sits behind a defocused foreground, obtained without moving the camera.

---

### 日本語

#### 絞りを積分する被写界深度 (Camera settings → APERTURE INTEGRATION)

写真撮影時のみ。このMODがこれまで出荷してきたデフォーカスも、並べて調査した82個の
シェーダーパックのそれも、例外なく「ピンホール画像1枚から、レンズがそれに何をしたはずかを
再構成する」ものだった。`evf_blur.fsh` の gather は丁寧な再構成だが、再構成である以上
逃れられないものを二つ抱えている。**ぼけた前景の裏に何があるかの記録を持たない**こと
(1枚の画像はそれを撮っていない)、そして**被覆率をタップ数で推定する**こと
(`sqrt(p(1-p)/N)` の二項誤差が、サンプル数を積む理由そのものになっている粒状感)。

レンズはそのどちらの問題も持たない。何も再構成していないからだ。入射瞳の全ての点を
通して同時に光を集め、絵はその総和である。だからシャッターは、まさにそれを行う
バーストフレームを費やす。各サブフレームは瞳上の1点から世界を描き、写真はその平均。
遮蔽が正しく出るのは、各サブフレームが自分の視点から自分の深度をラスタライズするから
——ある視点で手前の枝に隠れている背景は、別の視点では普通に見えている。gather が
「既知の制限」として文書化していたものが、緩和ではなく消滅する。被覆率を推定しないので
二項ノイズも存在しない。

**同じレンズであること**を仮定ではなく検証した。光学まで変わるモードは「より良いカメラ」
ではなく「別のカメラ」だからだ。瞳スイープが与える錯乱円は
`CoC_px = f_mm · PxPerMm · D_mm · |1/F_mm − 1/z_mm|`。これは shader が既に計算している
薄レンズの錯乱円そのもので、`D_mm = f/N` は絞りリングが常に追ってきた入射瞳と同一。
50mm f/2 と 100mm f/2.8、ワールドスケール1ブロック=1m〜1cm、被写体距離の1/3から無限遠までの
全点で **一致誤差 0.0000%**、焦点面の瞳横断ドリフトはゼロ。

**リニア光で積算する。** レンズが足しているのは放射輝度で、フレームバッファが持っている
のはガンマ符号化された数値。それを平均するのはサンプルが食い違う全ての場所で間違った和に
なる——ボケの縁、つまり効く場所の全部だ。サブフレームはデコードしてから加算する。センサー
側の非線形処理も一緒に後ろへ送った。ダイナミックレンジカーブ、トーンカーブ、ハイライト
ロールオフは「完成した1回の露光」を読むセンサーの仕事であって、200枚の部分露光それぞれの
仕事ではない。`EvfBlurRenderer` はサブフレームでこれを飛ばし、`ApertureIntegration.finish`
が完成した和に1度だけ適用する。

**各サブフレームはピンホールではなく小さな絞り。** n枚のバーストは瞳を連続的に
サンプルしない。直径方向に `sqrt(n)` 個並べるだけで、隣同士の間には隙間がある。ピンホールの
ままだとボケ円が被写体の `sqrt(n)` 個のハードコピーとして到着する——64サンプルで8つの
ゴースト、ジオラマスケールでは同じ64個が数百px幅の錯乱円に散らばって縞になる。そこで
サブフレームは瞳の1**セル**(`D/sqrt(n)` 幅)を代表し、そのセル自身のF値 `N·sqrt(n)` で
gather を受ける。セルは切り出された瞳を隙間なく敷き詰めるので、そのぼけの和は全開絞りの
ぼけに一致し、各セルはちょうど隣までの隙間を覆う——隙間とセルの錯乱円は同じ量
(どちらも全体の円を `sqrt(n)` で割ったもの)だからだ。

**前景は gather ではなくバーストのもの** (`evf_blur.fsh` の `FarOnly`)。両方には持たせられ
ない。gather で近接物体をぼかすとは、それを背景の上に広げ、空いた穴を「錯乱円1個分まで
離れた場所からの平均」で埋めることであり、それは1枚の画像が知り得ない唯一のもので、かつ
構造的に滑らかである。バーストの視差はまさにその背景を**見せる**ために存在する。別の瞳点
からはそもそも隠れておらず、通ってくるのは合焦した本物のディテールだ。300mm f/1.4 の
鉄格子で実測: 格子の帯の内側に残るディテールは、隣の隙間のディテールの11〜13%——背景は
通過していたのではなく、再構成されて失われていた。

#### 視点は行列ではなくカメラを動かす

バーストは以前、投影行列をシアーしていた。教科書通りの accumulation buffer 構成で、
ラスタライズしかしないレンダラには完全に正しく、行列について**推論する**レンダラには
静かに間違っている。Photon は投影を要素ごとに組み直す——

    combined_projection_matrix_2 = vec4(gbufferProjection.2.0, gbufferProjection.2.1, ...)
    combined_projection_matrix_3 = vec4(0.0, 0.0, ..., 0.0)

——シアーの位置合わせ項は保ったまま、シアーが視点の並進を書き込んでいる列を**ゼロにし**、
その「半分だけの視点移動」を `ssao` / `gtao` / `ssrt` / `raytracer` /
`d4_deferred_shading` / 雲の再構成に渡す。バーストは明るさが1.85倍振れて返ってきた。
しかもシーンの何かではなく**瞳オフセットの符号**に同期して。1.5cmの眼の移動で、絵がそこまで
変わるはずがない。

画面中央にタワーを建てて影を見る、という切り分けで確定した。52秒の露光を通じて影は0px
しか動かず、時計と太陽と雲が一度に容疑から外れ、シアーそのものに反応する何かだけが残った。

そこで並進は `Camera.moveBy` へ——動く視点が本来属するモデルビューであり、プレイヤーが
歩いたときに起きるのと同じことで、どのパックも完璧に扱える。投影に残るのは焦点面を
固定する項だけ、`m20`/`m21`——パックが読んで保存する場所だ。旧構成と同一であることを検証済み:
焦点面の位置合わせは瞳全域で小数9桁まで一致し、掃引される円は1ブロックから無限遠までの
全ての深度で一致する。光学は変わっていない。部品の**書き込み先**だけが変わった。

サンプル順序も黄金角ディスクから連続スパイラルへ変えた。黄金角は点を美しく散らすが、
訪問順序が最悪だ——連続するサンプルが瞳の反対側に落ち、旧設定では1フレームで0.65ブロック、
ダッシュ9回分を移動する。スパイラルに巻けば同じ点を0.09ブロック刻みで踏む。ダッシュ相当の
速度で、パックが文句なく追随することが実証済みの領域だ。収束はサンプルごとに買い直すのを
やめ、最初に1回だけ買って持ち回る (20フレーム、Photon自身の `CLOUDS_ACCUMULATION_LIMIT`)。
64サンプルのコストは1344フレームから148フレームになった。

**シェーダーパック使用時もバーストが使えるようになった。**

#### シャッター速度が、本当に露光時間になった

写真は二重積分だ。レンズは入射瞳の全ての点から**同時に**、かつシャッターが開いている全ての
瞬間に**わたって**光を集め、絵はその両方についての和である。バーストは瞳の方は正直に
サンプルしていたが、時間の方はレンダラの時計を全部止めていた。つまり露光時間ゼロ、
1/∞ 秒——どのカメラにも存在しないシャッター速度。ダイヤルは飾りで、30秒も1/4000も同じ
凍った空を返していた。

そこでサブフレーム i は、瞳上の1点であると同時に露光中の1瞬間になった。

    τ_i = ((i + 0.5) / n) · T        T = シャッター速度[秒]

レンダラがアニメーションを駆動している時計は全て、凍結ではなく τ から駆動する。瞳を覆うのと
同じ層化サンプリングが、そのまま露光を覆う。**凍結は間違った答えだったのではなく、T を
落とした問いに対する正しい答えだった。**

時計は3つある。

- `World.getTimeOfDay` — 太陽、月、空の色、そして `world_age` 経由でシェーダーパックが雲を
  流す風。
- バニラの雲ティック。レンダ引数として届く、ワールド時計とは別物。
- Iris の `frameTimeCounter`。3つの中で唯一サブティック分解能を持つ——草・水面・オーロラ・
  流れる霧。Iris は `beginFrame` 呼び出し間の差分でこれを進めるので、引数に仮想時計を
  渡すだけでいい。Iris のフィールドには一切触らない。`frameTime` も同じ引き算から出るので、
  パック側のフレームレート非依存な平滑化も一緒に露光速度まで落ちる。

64サンプルでの実際:

| SS | ワールド時計 | 見え方 |
|---|---|---|
| 1/1000 s | +0.02 tick | 何も動かない。それが 1/1000 秒の意味 |
| 1/60 s | +0.33 tick | ほぼ静止。葉の揺れが17ms分だけ |
| 1/4 s | +5 tick | 草と水面が溶け始める |
| 1 s | +20 tick | 雲が流れ、水がガラスになる |
| 30 s | +600 tick | 太陽が9°回る。影が掃き、雲は18ブロック尾を引く |

**エンティティも露光時計に乗せた。** ここが一番厄介だった。Mobは時計から描かれるのではなく
tickループがシミュレートしていて、クライアントMODがtickループを止めるのは論外だ
(サーバーでdesyncする)。実測はこうだった:

| SS | 露光時計 | バーストの実所要 |
|---|---|---|
| 1/15 s | 1.33 tick | 1912 ms ≈ 38 tick |
| 1/60 s | 0.33 tick | 1555 ms ≈ 31 tick |
| 1/250 s | 0.08 tick | 1620 ms ≈ 32 tick |

1/250秒と書かれた写真が、32tick分の歩行を抱えていた。シャッターダイヤルは世界の全てを
動かして、その中で人が実際に撮る唯一のものだけを動かしていなかった。

「実時間ペーシングに任せる」——1秒の写真は実時間で1秒かかるのだからMobは本当に歩く——は
正しそうで正しくない。**どのサンプルがどの瞬間に落ちるかがフレームの到着時刻任せ**になり、
露光が不均等に、しかもfps毎に違うふうにサンプルされる。0.5秒以下ではそもそもフレームが
入りきらない。瞳の位置と同じで、**瞬間も選ばなければならない**:

    e_i = ((i + 0.5) / n) · T · 20        サンプル i の露光tick

1/20秒は1tickに収まって全サンプルがその中。1/10秒は2tickなので最初が1tickめ、最後が2tickめ。
1/2秒は10tickで、最初が1tickめ、最後が10tickめ。

**記録してから再生する。** エンティティの見た目は「2tick分の補間ブラケット + 位相」の純関数で、
`EntityRenderer.getAndUpdateRenderState(entity, φ)` は現在tick内の任意の φ について完成した
`EntityRenderState` を返す——位置、体の向き、頭の向き、**手足の振り、腕の振り**、年齢、
持ち物、ポーズ、全部。tick内なら位相は自由、tickをまたぐと自由でない (ブラケットが進むから)。

だからシャッターは**露光と同じ長さのRECORDフェーズ**に開く。各クライアントtickの直後の
描画フレームで、そのtickが担当する瞬間の状態をバニラに作らせて保持する。最後の1つが
入ったらEXPOSEフェーズが瞳サンプルをフレーム速度で走らせ、各サブフレームは自分の e_i 用に
記録された状態を受け取る。**手で補間しないしフィールドを列挙もしない**ので、Mobの脚と
振り上げた腕と首と位置が互いに矛盾しない——全部1回の呼び出しから出てきたものだから。

検証したスケジュール (32サンプル):

| SS | 露光(tick) | 記録tick数 | 記録の実時間 | 最初のサンプル | 最後のサンプル |
|---|---|---|---|---|---|
| 1/1000 s | 0.02 | 1 | 0.05 s | 0.000 | 0.02 |
| 1/20 s | 1.00 | 1 | 0.05 s | 0.016 | 0.98 |
| 1/10 s | 2.00 | 2 | 0.10 s | 0.031 | 1.97 |
| 1/2 s | 10.0 | 10 | 0.50 s | 0.156 | 9.84 |
| 30 s | 600 | 591 | 29.6 s | 9.375 | 590.6 |

撮影の総コストは **T (記録 = 露光そのもの。本物のカメラも同じ値段を払う) + バーストのフレーム**。
1/250秒なら1tick、30秒なら30秒。実時間ペーシングは廃止した——記録が露光の長さを持つので、
バースト自体はフレームが来る速さで走ってよくなった。

シミュレーションには一切触れていない。記録した状態は描画側が毎フレーム作って捨てるスクラッチ
オブジェクトで、そのコピーを持っていることはtickループにもサーバーにもエンティティ自身にも
見えない。

制限: 記録する瞬間は最大64個 (それ以上はコピーが自分の幅より狭く重なる)。途中で視界に入った
エンティティは表に無いので、記録済みの最も近い瞬間にフォールバックし、それも無ければ通常描画。
パーティクルは別システム・別tickで未対応。1.21.2未満はレンダーステートが存在しないので未対応。
シングルプレイでポーズすると tick が止まるので、露光+5秒でタイムアウトして記録できた分で焼く。

#### 前景を「切り取って」その裏を見る機能は撤去した

近クリップ面を前景の向こうへ押し出してもう一度描く、という手はこのバージョンの途中まで
入っていた。同じ問い(ぼけた前景の裏に何があるか)への、カメラを動かさない答えとして。
**バーストがシェーダーパック下でも動くようになった時点で不要になった。** 積分は瞳の全方向から
遮蔽を解くのに対しピールは1層しか剥がせず(柵の前の柵は未解決のまま)、追加フレームを1枚食い、
gather の fill を別経路にする分だけシェーダも複雑にする。同じ問いへの弱い方の答えを2つ
抱えておく理由がない。設定項目・シェーダのサンプラ・近クリップ操作・専用の撮影パスすべて削除。

#### センサーサイズがノイズを決めるようになった

ISOは増幅率であって光の量ではない。粒状感を決めるのは各フォトサイトが捕まえた光子の数で、
同じF値・同じシャッターなら、小さいセンサーはそれに比例して小さい面積でそれを集める。
ショットノイズは個数の平方根で効くので `1/sqrt(面積)` ——つまりクロップファクタそのもの。
これは写真家が既に使っている等価ISOの規則だ。マイクロフォーサーズのISO400はフルサイズの
ISO1600相当に粒つく、2段、まさにこれが生む2倍。センサー選択はこれまで画角と被写界深度を
変えるだけで、大きいセンサーを買う本当の理由だけを触っていなかった。

#### その他

- **バースト診断ログ** — シャッター時に1行、和が閉じたときに1行。瞳径(ブロック)、スケール、
  フォーカス距離、サブ絞りを出す。`println` 経由: Minecraft の stdout キャプチャは
  `println` をラップしていて下のストリームはラップしないので、`printf` の診断はどこにも
  出ず、その不在は「バーストが起動しなかった」のと見分けがつかない。
- **`SnapmaticaClient.imageDistanceMmPhysical`** — 画角が呼吸することを許すかどうかに
  関わらず、像が実際に結ぶ位置。ピント合わせは像面を動かし、画角がそれに追従するのが
  目に見える帰結で、`focusBreathing` が切るのはその帰結の方。距離そのものは任意ではなく、
  ボケ円はそれに比例するので、瞳は本物の像距離に対して寸法を取る——さもないと画角補正が
  こっそりボケを縮め、両経路が `S/(S−f)` だけ食い違う (3mで1.7%、マクロ域では1/3増し)。
- **常時DoFの初期値を揃え、走る平均から外した。** ワールドスケールはカメラ側と同じ
  1blk=38cm を初期値にし(常時モードの既定は元から同じ定数だが、揃っていない設定が残りやすい)、
  絞りの初期値を f/5.6 → **f/4** に。この2つは別の仕事をする数字で、カメラ側は写真家が今から
  回すダイヤルの出発点、常時モード側は**メニューを一度も開かない人にとっての効果の全部**だから。
  50mm・8ブロック合焦での遠景ボケ:

  | スケール | 絞り | 遠景ボケ |
  |---|---|---|
  | 1blk=1m | f/2.8 | 4.7 px |
  | 1blk=38cm | f/5.6 | 6.4 px |
  | **1blk=38cm** | **f/4** | **8.9 px** |
  | 1blk=38cm | f/2 | 17.8 px |

  <p>**走る平均は常時DoFでは動かない**(ファインダー時のみ)。平均は視点が静止している間しか
  深まらないので、止まるたびに画が綺麗になり歩くたびに汚れる——プレイ中ずっと効いている効果が
  「動いているかどうか」で質感を変えるのは、レンズではなくレンダラの不調に見える。ファインダーは
  絵を待っている人が意図的に止めているので、綺麗になってくるのは構わない。
- **ライブビューを時間方向に平均する** (Camera settings → 時間積分(ノイズ低減))。gather は
  「決まったタップ数」を「光学が要求するどんな円板」にも配るので、極端な設定では予算が足りない。
  実測: 500mm f/2・375mm/blk で、焦点面の20ブロック手前は錯乱円が**609px**を要求し、そこへ
  入る384タップは **1タップあたり3000px²** ——55px角のセルが1画素の色を決めている。1フレームでは
  勝てず、明るい長玉でだけ出るあの大きく滑らかなブロッチになる。
  <p>ファインダーにはシャッターに無いものがある: **次のフレーム**。独立なノイズをk枚平均すれば
  `sqrt(k)` で減るので、48枚で**約7倍クリーン＝12,000タップ相当**が、フルスクリーン合成1回と
  ジオメトリ増加ゼロで手に入る。「独立な」が条件の全てで、だからバースト用に作った
  フレーム毎のノイズ回転とオフセットをここでも回す(固定タイルだと自分自身と平均するだけ)。
  現在フレームの近傍min/maxに履歴をクランプしてゴーストを止める。
  <p>**やらなかったこと。** 最初の版は瞳も歩いていた(毎フレーム1点、振幅は瞳の1/5)。バーストと
  同じく平均が真の遮蔽に収束し、走る平均が動きを隠すはず、という理屈で。収束はする。動きは
  隠れない——**静止したHUDの下で世界が泳ぐ**、60Hzで。これは機能ではなく乗り物酔いの作り方だ。
  オプションにせず削除した。正直に書くと「気分が悪くなる可能性があります」になるものは
  オプションではない。
  <p>分離できたことが収穫だった。**遮蔽には視差が要る＝カメラが動く必要がある。ノイズには
  カメラの移動は一切不要**で、サンプリングパターンが変わればいい。だからライブは見ていて
  コストのかからない方の半分だけを取り、前景の裏を見ることは元通り**写真の性質**のまま——
  シャッターなら眼を動かす余裕があり、誰もそれを見ていない。
- **一覧のスクロールが重かったのを直した。三つ別の問題だった。**
  <p>**サムネイルが存在しなかった。** 各セルは `texture()` を呼んでいて、それは初回に
  **フル解像度で、描画スレッド上で、同期に**デコードして同じサイズのテクスチャを返す。
  116pxのセルに200万画素をアップロードしていたわけだ。しかもキャッシュ上限が48枚なので、
  広いグリッドでは追い出した写真を**次のフレームでまた要求**する——スクロールが同じ写真を
  延々と再デコードしていた。長辺320pxに縮めて(画素数は約1/100)、**デコードはワーカーへ**、
  GPUアップロードだけ描画スレッドで**1フレーム2枚まで**。縮小は最近傍でなく箱平均——
  20分の1に間引いた写真は「小さな風景」ではなく「小さなノイズ」になるので。
  ビューアは従来通りフル解像度を読む(1枚を意図して大きく見る場所で、サムネイルは嘘になる)。
  <p>**UIが潰れた。** 選んだ密度が窓幅を無視していた。密度は**要求であって約束ではない**ので、
  セルが54pxを切る列数は窓の側が拒否する。
  <p>**端で一周した。** 一番密なところからさらに回すと一番疎に戻っていた。密度コントロールでは
  なくただの驚きなので、巡回をやめて両端で止める。
- **写真一覧に表示数を持たせ、スクロールバーを掴めるようにした。** 一覧は窓の幅だけから
  列数を決めていた。既定としては正しいが、**唯一の選択肢としては間違っている** — コンタクト
  シートは撮影者が探している密度で読むもので、「一度に何枚」がその全部だからだ。左下の
  ボタンで 自動 / 3 / 4 / 5 / 6 / 8 / 10 を巡回し、設定に残る。
  <p>スクロールバーは飾りだった(幅3px、当たり判定なし)。掴んで動かせるようにし、溝を
  クリックすればそこへ飛ぶ。掴む場所はつまみの中の位置を保持するので、掴んだ瞬間に絵が
  飛ばない。幅は6px、当たり判定は14pxで、カーソルが入ると明るくなる。
  <p>**Ctrl+ホイールでも変えられる。** カメラは再生時にズームレバーでこれをやる——1枚、4枚、
  9枚、36枚——しかも**それらの間を移動するのと同じコントロール上の同じ操作**なので、ボタン
  だけでなくホイールに載っているべきものだ。1.21.11 が `Screen.hasControlDown()` を廃止した
  ので修飾キーは `CameraScrollHandler` 経由で聞く。あのクラスは GLFW に尋ねる版分岐を
  既に持っていたので、**分岐を2つに増やさず1つを共有**した(ついでにあちらの重複も畳んだ)。

- **ファインダーの時間積分を削除した。** 走る平均は止まっている間だけ深まるので、
  ノイズは確かに減るが**見ていて気持ちが悪い**。前の版で瞳ウォークを消したときと同じ判断で、
  オプションにせず消した。設定項目・履歴バッファ・シェーダのPass 8・ユニフォーム2本、全部。
  露光バーストの側は無傷で、ノイズの多い設定(明るい長玉)ではサンプル数を上げるのが手段になる。
- **Mobにも自分のブラーを。** カメラ由来のスミアは画面全体で1本のベクトルで、静止フレームを
  横切るMobは**自分のベクトル**を必要とする。そして新しい測定は要らなかった——**露光は既に
  記録済み**なので、Mobの速度はそこにあるデータの引き算でしかない。記録した各瞬間の
  レンダーステートから位置と寸法を取り出し (`state.x/y/z/width/height`)、サブフレームの
  切れ幅で差分を取れば、それがそのMobの速度になる。
  <p>**どの画素がMobかは画面の矩形ではなくカメラ空間で決める。** 画素自身の位置は
  `gl_FragCoord` と深度から復元できるので、判定は「その点がMobの箱の中か」——3次元なので、
  後ろの壁も足元の地面も、矩形が外れることを祈るのではなく**自分の深度で除外される**。
  箱はスライス中の移動ぶんだけ掃引して広げてあるので、切れの途中で捕まえたMobも全区間で
  箱の中にいる。
  <p>基底はMinecraft自身のもの (yaw 0 が +Z、正のpitchが下向き) で、ピッチ込みのフル基底。
  画素の復元高さがピッチに依存するため。5ケースで検算済み: 真正面、頭上2ブロック、西3ブロック
  (=画面右)、下向き30°で同高度の物体が画面上方に来ること、yaw 90°。
  <p>1サブフレームあたり最速8体まで。1px未満しか動かないものは既にコピーが接しているので
  スロットを使わない。
- **サブフレームは瞬間ではなく露光の一切れ。** パンすると像が重なって見えた。原因は
  **時間方向のエイリアシング**で、n個の瞬間を平均するのは多重露光であってモーションブラーでは
  ない。コピーは1px未満に詰まって初めて筋になるので、背景を200px運ぶパンを32サンプルで
  撮ると **6px間隔のゴーストが32枚**並ぶ。サンプル数を増やすのは高い解決策。
  <p>安い方はこのMODが既にもう一方の軸で使っている論法だ。サブフレームはピンホールではなく
  瞳の1セルで、そのセル自身のF値で gather がぼかす——**まったく同じ理屈で、サブフレームは
  瞬間ではなく露光の一切れであり、その切れ幅ぶんだけ自分の運動方向に伸ばすべき**。セルが瞳を
  敷き詰め、切れがシャッターを敷き詰める。どちらも「サンプルする」のではなく「和が全体になる」。
  <p>スミア機構自体は長時間露光アキュムレータ用に既にあったが、`PhotoCapture.isLongExposing()`
  でゲートされていてバースト中は完全にオフだった。**記録済みカメラ経路**から
  `T/n` ぶんの変位を取って繋いだ。記録経路から取るのが肝で、瞳の移動は入らない——あれは絞りの
  ものでバーストが既に積分済み、二重にぼかすことになる。
  <p>カメラのスロット補間も入れた。スロット数は64で頭打ちなのにサンプル数はそうでないので、
  128サンプル×64スロットだと連続する2枚に**同じ視点**を渡していた。平均の中の同一2枚は
  「半分のサンプル」ではなく「二重像」だ。
  <p>静止カメラ+動くMobには、この上にMob自身のベクトルが要る。下記。
- **ボケの「かかり始める線」を消した。** gather は錯乱円から固定量を引いてから使っていた
  (`max(coc - 1.5, 0)`)。これは**崖**だ。1.5pxまでデフォーカスがちょうどゼロで、タップ毎の
  ディスク判定 (`sCoc >= 0.5`) がさらに0.5px要求するので、真の円が2.0pxに達するまで何も
  起きず、そこで半px幅のディスクが**フル重みで点灯**する。最内タップの重みは実測で
  **0.000 → 0.945 の階段**。焦点面から滑らかに遠ざかる面ではこの閾値が**等高線**になり、
  ボケの始まる線として画面に描かれる。ずっとあったが、周りの品質が上がって初めて
  フレーム内で一番目立つものになった。崖をやめて膝にした:

      cocGather = c² / (c + K)          K = 1.5 px

  ゼロでゼロ、ゼロで**傾きもゼロ**——ボケは点灯せず滲み出す。50pxの時点で `c - K` と0.03px
  以内に一致し、その間ずっと単調かつ滑らか。床は仕事をしたまま (真の円1pxは0.4pxとして、
  0.5pxは0.13pxとして描かれ、どちらも1画素が表現できる下)、答えが跳ぶ深度が無い。
  <p>タップ側も直した。羽根幅を**ディスクに比例させる** (`feather = clamp(sCoc, 0.02, 0.5)`)。
  固定0.5pxだと sCoc がいくつでも `sCoc + 0.5` まで拾うので、0.1pxのディスクでも1/5px先の
  隣接ピクセルを意味のある重みで引き込み、gather が恒等写像に**収束しなかった**。それを
  隠していたのが `sCoc >= 0.5` のゲートで、それ自体が崖だった。シャープ早期脱出も 0.5 → 0.07
  に。ハンドオーバー時の gather は 24タップ・半径1が下限なので最内タップは
  sqrt(0.5/24) = 0.144px、羽根は 2·cocP までしか拾わない → 0.07 なら**重みちょうどゼロ**。
  <p>閾値をディザで散らす手もあったが、それは1本の間違った輪郭を帯全体のノイズに変えるだけで、
  誤差を隠しただけで消していない。不連続は関数の側にあり、関数はこちらのものだ。
- **カメラも露光時計に乗せた。流し撮りができる。** 露光中に視点を固定すると、動く被写体が
  流れて背景が止まる——誰も欲しくない絵になる。被写体を追えば被写体が止まって背景が流れる。
  それが撮りたい絵だ。だからRECORDフェーズは撮影者のもの (歩く・振る・追う)、その後の
  バーストは**各瞬間にどこにいたかを再生する**。エンティティと同じサブtick位相でプレイヤーから
  記録している点が効く: カメラと被写体の位相が1/3tickずれるだけで歩行Mobは画面上を
  0.066ブロック引きずられ、いつもの画角なら約8pxのブレになる——シャープに写るはずだった
  唯一のものが。
  <p>露光中に殺すのはジャンプとスニークだけ (1.21.4+)。どちらも視点を誰も頼んでいない弧で
  揺らすし、露光はそれを再生する——0.5秒シャッター中の一跳ねは写真の中では跳ねではなく、
  中間の全高さが同時に写る。歩きと視点操作はそのまま生きる。それが目的だから。
- **バースト前のウォームアップを20フレームから4へ。** 20は視点が*テレポート*していた頃の
  数字だった。シアーが並進を Photon がゼロにする列に書いていたので、パックは再投影できない
  半端な視点変化を見て履歴を捨て、Photon 自身の `CLOUDS_ACCUMULATION_LIMIT` = 20 フレームを
  かけて最も遅いバッファを組み直していた。その理由はもう無い。並進はモデルビューにあって
  プレイヤーが歩くのと区別がつかず、スパイラルの隣接ステップは0.09ブロックなので再投影は
  **成功する**。シャッター時に不連続なのは、ファインダーを開かずに押した場合の画角と、
  時計の「値」ではなく「レート」が変わることだけ——それぞれ1フレーム分。4であって0でないのは
  これが導出であって実測ではないから。`apertureDebugSamples` でサンプル毎のゲインを見て、
  最初の数枚が他と同じに測れていれば0にできる。
- **旧バージョンで起動時クラッシュしていたのを修正。** `Camera.getPos` は1.21.10で
  `getCameraPos` に改名されており、`moveBy` は1.21未満では double を取る。`@Shadow` は
  ターゲットが実際に持つ名前でなければ mixin が適用されずゲームが起動しない——1.3.0の
  6ビルド中4つがその状態だった。両方にバージョン分岐を入れ、6ターゲット全てが警告ゼロで
  ビルドされることを確認。
- **シアーは「カリングする行列」ではなく「描く行列」に当てる。** `renderWorld` は2つの
  投影行列を扱うが、ピクセルを置くのは片方だけ。GPUに届くのは `RawProjectionMatrix.set`
  を通る方で、`getProjectionMatrix(fov)` の方は錐台カリング用に `WorldRenderer.render` へ
  渡される。最初の実装は後者を動かしていた——1ピクセルも変わらない。バーストは走り、
  サブフレームは全て同一で返り、同一な64枚の平均は1枚のシャープな絵だった。

#### 既知の制限

- 細い前景がどこまで**溶けるか**は、掃引円が物体自身の幅に対してどうかで決まる
  (`sweep/width = D_blocks·|z/F − 1| / w`)。デフォルトの1ブロック=1mでは、マイクラの
  鉄格子は12.5cmの鋼管であって針金ではない。50mm f/1.4 で0.5m先なら562px幅に対して掃引は
  157pxなので、縁が柔らかくなって芯は残る——本物の12.5cm支柱がそうなるように。掃引が幅を
  追い越すと消える: ワールドスケールが最も強いレバー (1ブロック=5cmで残留15%、1cmで4%)、
  長いレンズも効く (200mmでデフォルトスケールでも等倍に届く)、本当に細い遮蔽物なら
  どちらも要らない。
- 機械的ヴィネッティング (gather が解析的に当てているキャッツアイ) は積分経路では
  モデル化していない。円は丸いまま。正しくやるにはサブフレームごとのピクセル別重みが要る。
- エンティティは露光時計に乗らない (上記)。1/30秒より速いシャッターでは、動くMobだけが
  シャッター速度ではなくバーストの実所要時間で尾を引く。

---

### English

#### Depth of field by integrating the aperture (Camera settings → APERTURE INTEGRATION)

For the photograph only. Every defocus this mod has shipped until now — and every one in the
eighty-two shader packs surveyed alongside it — starts from a single pinhole image and
reconstructs what a lens would have done to it. `evf_blur.fsh`'s gather is a careful
reconstruction, but it inherits the two things a reconstruction cannot escape: it has no record
of what sits behind a defocused foreground, because one image never captured it, and it
estimates coverage by counting taps, a binomial trial whose `sqrt(p(1-p)/N)` error is the grain
the sample budget exists to fight.

A lens has neither problem, because it is not reconstructing anything — it collects light
through every point of its entrance pupil at once and the picture is the sum. So the shutter
now spends a burst of frames doing exactly that: each sub-frame renders the world from one point
on the pupil, and the photograph is their average. The occlusion comes out right because each
sub-frame rasterises its own depth from its own viewpoint — the background hidden behind a near
branch in one is plainly visible in another — which is the known limitation the gather documents,
gone rather than mitigated. There is no coverage to estimate, so there is no binomial noise to
buy off.

**It is the same lens.** Checked rather than assumed, because a mode that changed the optics as
well as the method would be a different camera, not a better one. The pupil sweep gives
`CoC_px = f_mm · PxPerMm · D_mm · |1/F_mm − 1/z_mm|`, which is the thin-lens circle of confusion
the shader already computes, with `D_mm = f/N` — the same entrance pupil the aperture ring has
always tracked. Measured at 50 mm f/2 and 100 mm f/2.8, at world scales from a metre a block to
a centimetre, focused from a third of the subject distance out to infinity: agreement to
**0.0000%** at every point, and the focal plane holds to zero drift across the pupil.

**Accumulated in linear light.** A lens sums radiance; the framebuffer holds gamma-encoded
numbers, and averaging those is the wrong sum everywhere the samples disagree — which, at the
edge of a bokeh disc, is everywhere that matters. Sub-frames are decoded before they are added.
The sensor's own non-linear steps are held back with them: the dynamic-range curve, the tone
curve and the highlight rolloff are the sensor reading one finished exposure, not each of two
hundred partial ones, so `EvfBlurRenderer` skips them per sub-frame and
`ApertureIntegration.finish` applies them once, to the completed sum.

**Each sub-frame is a small aperture, not a pinhole.** A burst of `n` views does not sample the
pupil continuously — it lays `sqrt(n)` of them across the diameter, and between neighbours there
is a gap. Left as pinholes a bokeh disc arrives as `sqrt(n)` hard copies of the subject: eight
visible ghosts at 64 samples, and at a diorama world scale they separate into stripes. So a
sub-frame stands for one CELL of the pupil, `D/sqrt(n)` across, and gets the gather at that
cell's own f-number — `N·sqrt(n)`, since `N = f/D`. The cells tile the pupil they were cut from,
so their blurs sum to the full aperture's, and each covers exactly the gap to its neighbour: the
gap and the cell's circle of confusion are the same quantity, both being the full circle over
`sqrt(n)`.

**The foreground belongs to the burst, not to the gather** (`FarOnly` in `evf_blur.fsh`). The
two mechanisms cannot both have it. Blurring a near object in the gather means spreading it over
the background and filling the hole it leaves with an estimate averaged from up to a whole
circle of confusion away — the one thing a single image cannot know, and smooth by construction.
The burst's parallax exists precisely to SHOW that background. Measured on a fence shot at
300 mm f/1.4: detail surviving inside the bars' band was 11-13% of the detail in the gaps beside
them — the background was not being passed through, it was being reconstructed and lost.

#### The viewpoint moves the camera, not the matrix

The burst used to shear the projection: the textbook accumulation-buffer construction, exactly
right for a renderer that only rasterises, and quietly wrong for one that reasons about the
matrix. Photon rebuilds the projection element by element —

    combined_projection_matrix_2 = vec4(gbufferProjection.2.0, gbufferProjection.2.1, ...)
    combined_projection_matrix_3 = vec4(0.0, 0.0, ..., 0.0)

— keeping the shear's registration term and ZEROING the column the shear also writes the
viewpoint's translation into, then feeding that half-a-viewpoint-change to `ssao`, `gtao`,
`ssrt`, `raytracer`, `d4_deferred_shading` and the cloud reconstruction. A burst came back with
its brightness swinging by a factor of 1.85, locked to the SIGN of the pupil offset rather than
to anything in the scene — an eye movement of a centimetre and a half, and a picture that could
not possibly have changed that much.

Diagnosed by building a tower in frame and watching its shadow: across 52 seconds of exposure it
moved 0 px, which ruled out the clock, the sun and the clouds in one measurement and left only
something that responds to the shear itself.

So the translation goes to `Camera.moveBy` — the modelview, where a moving viewpoint belongs,
and the same thing that happens when the player walks, which every pack handles perfectly. Only
the term that holds the focal plane still stays in the projection, in `m20`/`m21`, which packs
read and preserve. Verified identical to the old construction: focal-plane registration matches
to nine decimals across the pupil, and the swept circle matches at every depth from one block to
infinity. The optics did not change; only where their pieces are written.

Sample ordering changed with it, from a golden-angle disc to a continuous spiral. Golden angle
scatters points beautifully and visits them terribly — consecutive samples land on opposite
sides of the pupil, 0.65 blocks apart in a single frame at the old settings, nine times a
sprint. Wound into a spiral the same points step 0.09 blocks, which is sprinting pace and
demonstrably something a pack tracks without complaint. Convergence is bought once at the start
(20 frames, Photon's own `CLOUDS_ACCUMULATION_LIMIT`) and carried, instead of being re-bought at
every sample: 64 samples cost 148 frames rather than 1344.

**Aperture integration is no longer held back when a shader pack is drawing.**

#### The shutter speed is the exposure now

A photograph is a double integral, and until now this only did half of it. A lens collects light
through every point of its entrance pupil AND across every instant the shutter is open; the
picture is the sum over both. The burst sampled the pupil honestly and then stopped every clock
in the renderer, which makes the second integral a single instant — a shutter of 1/∞, the one
speed no camera has. The dial was decorative: 30 seconds and 1/4000 returned the same frozen sky.

So sub-frame `i` is not just a point on the pupil, it is a point in the exposure:

    τ_i = ((i + 0.5) / n) · T        T = the shutter, in seconds

and every clock the renderer animates from is driven off `τ` instead of being frozen at zero. The
same stratification that covers the pupil covers the exposure. **Freezing was never the wrong
answer; it was the right answer to a question with `T` left out of it.**

Three clocks, because Minecraft and Iris keep three:

- `World.getTimeOfDay` — the sun, the moon, the sky colour, and (through `world_age`) the wind a
  shader pack drifts its clouds along.
- the vanilla cloud tick, which arrives as a render argument and is a different clock from the
  world's.
- Iris's `frameTimeCounter`, the only one of the three with sub-tick resolution — waving foliage,
  water surface, aurora, drifting fog. Iris advances it by the gap between `beginFrame` calls, so
  handing that call a virtual clock is the whole of it: no field of Iris's is touched. `frameTime`
  falls out of the same subtraction, so a pack's framerate-independent smoothing slows down with
  everything else instead of running at wall-clock speed inside a virtual exposure.

What that buys, at 64 samples:

| Shutter | World clock | What it looks like |
|---|---|---|
| 1/1000 s | +0.02 tick | Nothing moves, which is what 1/1000 s means |
| 1/60 s | +0.33 tick | Still, bar 17 ms of leaf movement |
| 1/4 s | +5 ticks | Grass and water start to soften |
| 1 s | +20 ticks | Clouds drift, water goes to glass |
| 30 s | +600 ticks | Nine degrees of sun: shadows sweep, clouds streak 18 blocks |

**Entities are on the exposure clock too.** This was the hard one. A mob is not drawn from a
clock — it is simulated by the tick loop, and a client mod does not get to stop the tick loop,
which would desync the player on a server. Measured:

| Shutter | Exposure clock | What the burst actually took |
|---|---|---|
| 1/15 s | 1.33 ticks | 1912 ms ≈ 38 ticks |
| 1/60 s | 0.33 ticks | 1555 ms ≈ 31 ticks |
| 1/250 s | 0.08 ticks | 1620 ms ≈ 32 ticks |

A photograph marked 1/250 s was carrying thirty-two ticks of walking. The shutter dial moved the
whole world except the one thing in it that anybody photographs.

Letting real time carry them — the burst is paced, so a one-second photograph takes a real second
and the mob really does walk through it — sounds right and is not: **which instant each sample
lands on is then decided by when a frame happened to arrive**, so the exposure is sampled
unevenly and differently at every framerate, and below about half a second there is not enough
real time to fit the frames into at all. The instant has to be CHOSEN, the way the pupil position
is chosen:

    e_i = ((i + 0.5) / n) · T · 20        exposure tick of sample i

1/20 s spans one tick and every sample lives inside it; 1/10 s spans two, so the first sample is
at tick 1 and the last at tick 2; 1/2 s spans ten, first at 1 and last at 10.

**Record, then replay.** An entity's appearance is a pure function of its two-tick interpolation
bracket and a phase: `EntityRenderer.getAndUpdateRenderState(entity, φ)` returns a complete,
freshly built `EntityRenderState` for any `φ` inside the current tick — position, body yaw, head
yaw, **limb swing, arm swing**, age, held item, pose, every field. Within a tick the phase is
free; across ticks it is not, because the bracket has moved on.

So the shutter opens into a RECORD phase lasting exactly as long as the exposure. On the render
frame after each client tick, vanilla is asked for the states at whatever instants that tick
carries, and they are kept. When the last one is in, the EXPOSE phase runs the pupil samples as
fast as frames arrive and each sub-frame is served the state recorded for its own `e_i`. **Nothing
is interpolated by hand and no field is enumerated**, so a mob's legs, its swinging arm, its
turning head and its position cannot disagree with each other — they came out of one call.

The schedule, verified at 32 samples:

| Shutter | Exposure (ticks) | Ticks recorded | Real time | First sample | Last sample |
|---|---|---|---|---|---|
| 1/1000 s | 0.02 | 1 | 0.05 s | 0.000 | 0.02 |
| 1/20 s | 1.00 | 1 | 0.05 s | 0.016 | 0.98 |
| 1/10 s | 2.00 | 2 | 0.10 s | 0.031 | 1.97 |
| 1/2 s | 10.0 | 10 | 0.50 s | 0.156 | 9.84 |
| 30 s | 600 | 591 | 29.6 s | 9.375 | 590.6 |

Total capture is `T` — the recording, which IS the exposure, and is what it costs a real camera
too — plus the burst's own frames. Real-time pacing is gone with it: the recording carries the
exposure's duration, so the burst itself can run as fast as frames come.

Nothing touches the simulation. The recorded states are scratch objects the renderer builds and
throws away every frame; keeping copies is invisible to the tick loop, to the server, and to the
entity itself.

Limits: at most 64 distinct instants (beyond that the copies overlap by more than their own
width). An entity that comes into view late falls back to the nearest instant it was recorded at,
and draws live if there is none. Particles are a separate system with their own tick and are not
covered. 1.21.2 and up, since below that there is no render state. And a paused single-player
client stops ticking, so recording gives up at the exposure plus five seconds and exposes with
what it has.

#### Seeing behind the foreground by cutting it away has been removed

Pushing the near clip plane out past the foreground and drawing the frame again was in this
version for part of its life, as the answer to the same question — what sits behind a defocused
foreground — that does not move the camera. **It stopped being needed the moment the burst worked
under a shader pack.** The integration resolves occlusion from every direction of the pupil at
once; the peel exposes one layer (a fence in front of a fence stays unresolved), costs an extra
rendered frame, and puts a second path through the gather's fill. There is no reason to carry two
answers to one question when one of them is strictly weaker. The setting, the sampler, the near
plane push and its own capture path are all gone.

#### Sensor size now decides the noise, not just the framing

ISO is a gain, not a quantity of light; what sets the grain is how many photons each photosite
caught, and a smaller sensor at the same f-number and shutter catches them over a proportionally
smaller area. Shot noise goes as the square root of the count, so it scales as `1/sqrt(area)` —
the crop factor itself. That is the equivalent-ISO rule photographers already work by: Micro Four
Thirds at ISO 400 grains like full frame at 1600, two stops, exactly the factor of two this
produces. Choosing a sensor previously changed the framing and the depth of field and left
untouched the one thing a bigger sensor is actually bought for.

#### Also

- **Burst diagnostics** — one line at the shutter and one when the sum closes, naming the pupil
  in blocks, the scale, the focus and the sub-aperture. Through `println`: Minecraft's stdout
  capture wraps `println`, not the stream underneath, so a `printf` diagnostic goes nowhere and
  its absence from the log looks exactly like a burst that never armed.
- **`SnapmaticaClient.imageDistanceMmPhysical`** — where the image really forms, regardless of
  whether the FIELD is allowed to breathe. Focusing moves the image plane; the field of view
  following it is the visible consequence, and that consequence is what `focusBreathing` switches
  off to imitate a corrected lens. The distance itself is not optional, and the blur circle
  scales with it, so the pupil is sized against the real one — otherwise a framing correction
  would quietly shrink the bokeh, and the two paths would disagree by `S/(S−f)`: 1.7% at three
  metres, a third again at macro distances.
- **The ambient mode's defaults line up with the camera's, and it is off the running average.**
  Its world scale starts at the same 1 blk = 38 cm, and its aperture goes from f/5.6 to **f/4**.
  The two apertures do different jobs — the camera's is a starting point on a dial the
  photographer is about to turn, and this one is the whole of the effect for someone who never
  opens the menu. Far-field blur at 50 mm focused 8 blocks out:

  | Scale | Aperture | Far-field blur |
  |---|---|---|
  | 1 blk = 1 m | f/2.8 | 4.7 px |
  | 1 blk = 38 cm | f/5.6 | 6.4 px |
  | **1 blk = 38 cm** | **f/4** | **8.9 px** |
  | 1 blk = 38 cm | f/2 | 17.8 px |

  <p>**The running average is the viewfinder's only.** It deepens only while the viewpoint holds
  still, so every pause would clean the picture up and every step dirty it again — an effect that
  is running the whole time the game is being played must not change texture with whether the
  player happens to be moving, or it reads as the renderer misbehaving rather than as a lens. A
  finder is held still on purpose, by someone waiting for the picture, who will not mind it
  arriving.
- **The live view is averaged over successive frames** (Camera settings → Temporal
  Integration). A gather is a fixed tap budget over whatever disc the optics ask for, and at the
  extremes the optics ask for more than any budget covers. Measured at 500 mm f/2 with 375 mm to
  the block: a subject 20 blocks inside the focal plane wants a circle of confusion **609 px
  across**, and the 384 taps that go into it work out at **one tap per 3000 px²** — a 55 px cell
  deciding one pixel's colour. No single frame wins that, and the result is the broad, smooth
  blotching that turns up on a long lens wide open and nowhere else.
  <p>A viewfinder has something a shutter does not: the NEXT frame. Averaging k frames of
  independent noise divides it by `sqrt(k)`, so 48 frames is about **seven times cleaner — the
  same picture as twelve thousand taps** — for one fullscreen blend and no extra geometry.
  Independence is the whole condition, which is why the per-frame noise rotation and offset built
  for the burst run here too; with a fixed tile every frame carries the same pattern and
  averaging it with itself achieves nothing. The history is clamped to the current frame's
  neighbourhood so nothing ghosts.
  <p>**What it deliberately does not do.** The first version also walked the entrance pupil, one
  point per frame at a fifth of the excursion, on the reasoning that the average would converge
  on true occlusion the way the burst does while the running mean hid the movement. It does
  converge, and the movement is not hidden: **the world swims under a stationary HUD** at 60 Hz,
  which is a recipe for motion sickness rather than a feature. Removed, not made optional — an
  option whose honest description is "may make you ill" is not an option.
  <p>The useful part is that the two halves separate. Occlusion needs parallax and therefore
  needs the camera to move; noise does not need the camera to move at all, only the sampling
  pattern to change. So the live view keeps the half that costs nothing to look at, and seeing
  behind a defocused foreground stays what it always was — a property of the photograph, where
  the shutter can afford to move the eye and nobody is watching it happen.
- **The roll's scrolling was slow, and it was three separate things.**
  <p>**There were no thumbnails.** Every cell called `texture()`, which decodes the file at FULL
  resolution on the render thread the first time it is asked and returns a texture of the same
  size — two megapixels uploaded into a 116-pixel cell, synchronously, inside the frame that
  wanted to draw it. With the cache bounded at 48, a wide grid could evict a picture and be asked
  for it again on the very next frame, so scrolling re-decoded the same photographs over and
  over. Thumbnails are now 320 px on the long edge (about a hundredth of the pixels), **decoded
  on a worker**, with the GPU upload — the only part that has to be on the render thread —
  bounded at two per frame. The reduction is a box average rather than a nearest-neighbour pick:
  a photograph sampled every twentieth pixel is not a small picture of the scene, it is a small
  picture of the noise. The viewer still reads the real thing, since it shows one picture at full
  size on purpose.
  <p>**The layout collapsed.** A chosen density ignored the window. It is a request, not a
  promise, so the window now refuses any column count that would put cells under 54 px.
  <p>**It wrapped at the ends.** Running off the dense end reappeared at the sparse one, which is
  not a density control but a surprise. Clamped.
- **The camera roll has a density, and its scrollbar is a control.** The grid sized itself from
  the window and nothing else — the right default and the wrong only option, since a contact
  sheet is read at whatever density the photographer is looking for and "how many at once" is the
  whole of that. A button at the bottom left cycles Auto / 3 / 4 / 5 / 6 / 8 / 10, and the choice
  is kept.
  <p>The scrollbar was decoration: three pixels wide and not clickable. It can be dragged now,
  and clicking the trough jumps there. The grab point is held inside the thumb so the roll does
  not lurch the moment it is caught. Six pixels wide with a fourteen-pixel hit zone, and it
  lights up under the pointer.
  <p>**Ctrl+wheel changes it too.** A camera does this in playback with the zoom lever — one
  picture, four, nine, thirty-six — and it is the same gesture on the same control that moves
  through them, which is why it belongs on the wheel and not only on a button. 1.21.11 dropped
  `Screen.hasControlDown()`, so the modifier is read through `CameraScrollHandler`, which already
  owned a version branch for asking GLFW directly: **one branch shared rather than a second one
  added**, and its own duplicate folded into the same helper on the way past.

- **The viewfinder's temporal integration is removed.** The running average only deepens while
  the view is held still, so it does cut the noise and it is unpleasant to watch. Same judgement
  as the pupil walk before it: removed rather than made optional — the setting, the history
  buffers, the shader's Pass 8 and its two uniforms, all of it. The exposure burst is untouched,
  and more samples remains the lever where the gather is starved.
- **Mobs get their own blur.** The camera smear is one vector for the whole picture, and a mob
  crossing a still frame is the one thing not moving at the camera's speed. No new measurement
  was needed for it: **the exposure is already recorded**, so the mob's velocity is a subtraction
  on data that is sitting there. Each recorded instant's render state carries the body
  (`state.x/y/z/width/height`); differencing it across the slice a sub-frame stands for is the
  velocity.
  <p>**Which pixels are the mob is decided in camera space, not by a rectangle on screen.** The
  pixel's own position is recoverable from `gl_FragCoord` and its depth, so the test is whether
  that point is inside the mob's box — three dimensions, so the wall behind it and the ground
  below its feet are excluded on their own depth rather than by hoping a rectangle misses them.
  The box is swept by the slice's travel, so a mob caught mid-slice is inside it for the whole
  of it.
  <p>Built in Minecraft's own basis (yaw 0 looks down +Z, positive pitch looks down), with pitch
  included, since a pixel's reconstructed height depends on it. Checked against five cases:
  straight ahead, two blocks up, three blocks west reading as screen-right, an object at eye
  height appearing above centre while pitched 30° down, and a 90° yaw.
  <p>Eight movers per sub-frame, fastest first; anything travelling under a pixel is skipped,
  because its copies already touch.
- **A sub-frame is a slice of the exposure, not an instant.** Panning made the picture double.
  That is **temporal aliasing**: averaging n instants of a moving viewpoint is a multiple
  exposure, not motion blur, and the copies only merge into a streak once they are less than a
  pixel apart — so a pan carrying the background 200 px across a 32-sample exposure arrives as
  32 ghosts 6 px apart. More samples is the expensive way out.
  <p>The cheap way is the argument this mod already uses on the other axis. A sub-frame is not a
  pinhole, it is a CELL of the pupil, blurred by the gather at that cell's own f-number — and by
  exactly the same argument a sub-frame is not an instant, it is a SLICE of the exposure, and it
  should be smeared along its own motion by the width of that slice. The cells tile the pupil and
  the slices tile the shutter; in both cases what is drawn sums to the whole instead of sampling
  it.
  <p>The smear itself already existed for the long-exposure accumulator, but it was gated on
  `PhotoCapture.isLongExposing()` and so was entirely off during a burst. It is now fed the
  displacement over `T/n` taken from the RECORDED camera path — which is the part that matters:
  the pupil excursion is not in it. That displacement belongs to the aperture, the burst already
  integrates it, and smearing it as well would blur the picture by the entrance pupil twice.
  <p>Camera poses are interpolated between slots with it. The slot count is capped at 64 and the
  sample count is not, so at 128 samples two consecutive sub-frames were being handed the SAME
  viewpoint — and a pair of identical frames in an average is not half a sample, it is a doubled
  image.
  <p>A static camera with a moving mob needed its own vector on top of this; see below.
- **The line where the blur starts is gone.** The gather subtracted a fixed floor from the
  circle of confusion before using it (`max(coc - 1.5, 0)`), and that is a CLIFF: defocus is
  exactly zero out to 1.5 px, the per-tap disc test (`sCoc >= 0.5`) wanted another half pixel on
  top, so nothing at all happened until the true circle reached 2.0 px and then a half-pixel disc
  switched on at full weight. Measured, the innermost tap's weight steps **0.000 → 0.945** across
  that depth. On a surface receding smoothly from the focal plane the threshold is a CONTOUR, and
  it draws a visible line across the picture where the blur begins. It was always there; it only
  became the worst thing in the frame once everything around it got better. A knee instead of a
  cliff:

      cocGather = c² / (c + K)          K = 1.5 px

  zero at zero, with zero SLOPE at zero — so blur creeps in rather than switching on — within
  0.03 px of `c - K` by 50 px of blur, and monotone and smooth all the way between. The floor
  still does its job (a true circle of 1 px renders as 0.4, half a pixel as 0.13, both below what
  a pixel can show) without a depth at which the answer jumps.
  <p>The tap test was the other half. The feather now NARROWS with the disc
  (`feather = clamp(sCoc, 0.02, 0.5)`): a fixed half-pixel band admits taps out to `sCoc + 0.5`
  whatever `sCoc` is, so a disc of 0.1 px still pulled in neighbours a fifth of a pixel away at
  meaningful weight and the gather never became the identity — and the gate that hid that was
  itself the cliff. The sharp early-out drops from 0.5 to 0.07 with it: near the handover the
  gather is at its floor of 24 taps over a radius of 1, so the innermost tap sits at
  sqrt(0.5/24) = 0.144 px and the feather admits out to 2·cocP, which puts that tap **exactly at
  zero weight** rather than merely near it.
  <p>Dithering the threshold was the other option and is strictly worse: it turns one wrong edge
  into noise spread across the whole band, which is the same error with its structure hidden
  rather than the error removed. The discontinuity was in the function, and the function is ours.
- **The camera is on the exposure clock too, so you can pan.** Holding the viewpoint still
  through the exposure makes a moving subject streak and the background sharp, which is the
  picture nobody wanted; following the subject makes the subject sharp and the background streak,
  which is the shot. So the RECORD phase belongs to the photographer — walk, turn, track — and
  the burst afterwards REPLAYS where they were at each instant rather than averaging where they
  ended up. Recorded from the player at the same sub-tick phases as the entities, which matters
  more than it looks: a mismatch of a third of a tick between camera and subject drags a walking
  mob 0.066 blocks across the frame, about eight pixels of smear on the one thing that was
  supposed to come out sharp.
  <p>Jump and sneak are dropped for the duration and nothing else is (1.21.4+). Both bob the
  viewpoint through an arc nobody asked for, and the exposure replays them — a hop during a
  half-second shutter is not a hop in the picture, it is every intermediate height at once.
  Walking and looking keep working, because they are the point.
- **The pre-burst warm-up goes from 20 frames to 4.** Twenty was the number for a viewpoint that
  *teleported*: the shear wrote the translation into a column Photon zeroes, so a pack saw half a
  viewpoint change it could not reproject, threw its history away, and needed Photon's own
  `CLOUDS_ACCUMULATION_LIMIT` — twenty frames — to rebuild the slowest buffer it keeps. That
  reason is gone. The translation lives in the modelview now, indistinguishable from the player
  walking, and the spiral steps 0.09 blocks between neighbours, so reprojection SUCCEEDS. Nothing
  is discontinuous at the shutter any more except the field of view, if the finder was not
  already open when it was pressed, and the clocks changing rate rather than value — one frame of
  adjustment each. Four rather than zero because that is a derivation, not a measurement: turn on
  `apertureDebugSamples` and read the per-sample gains, and if the opening samples meter with the
  rest it can go to zero.
- **Fixed a crash on load for four of the six builds.** `Camera.getPos` was renamed
  `getCameraPos` at 1.21.10, and `moveBy` took doubles before 1.21. A `@Shadow` has to carry the
  name the target actually has or the mixin does not apply and the game does not start. Both now
  branch by version, and all six targets build with no warnings.
- **The shear went on the matrix that draws, not the one that culls.** `renderWorld` handles two
  projection matrices and only one of them puts pixels anywhere. The one that reaches the GPU
  goes through `RawProjectionMatrix.set`; the one from `getProjectionMatrix(fov)` is handed to
  `WorldRenderer.render` for frustum culling. The first attempt moved the second, which changes
  not a single pixel — the burst ran, the sub-frames came back identical, and the average of
  sixty-four identical frames is one sharp frame.

#### Known limits

- How far a thin foreground DISSOLVES is set by how the swept circle compares to the object's own
  width — `sweep/width = D_blocks·|z/F − 1| / w` — and at the default metre a block a Minecraft
  iron bar is a 12.5 cm steel post, not a fence wire. Half a block from a 50 mm at f/1.4 it images
  562 px wide against a 157 px sweep, so it softens at the edges and keeps a solid core, which is
  what a real 12.5 cm post does. It washes out when the sweep overtakes the width: a smaller world
  scale is the strongest lever (15% residual at 1 blk = 5 cm, 4% at 1 cm), a longer lens helps
  (200 mm reaches parity at the default scale), and a genuinely thin occluder needs neither.
- Mechanical vignetting — the cat's-eye clipping the gather applies analytically — is not modelled
  in the integrated path; the disc stays round. Doing it properly needs a per-pixel weight per
  sub-frame rather than an approximation layered on top.
- Entities are not on the exposure clock (above). Below 1/30 s, a moving mob is the one thing
  that trails over how long the burst took rather than over how long the shutter was open.

## 1.2.2

### Added

- **Ambient depth of field** — the lens applied to ordinary play rather than to a photograph,
  as a toggle. Every setting it uses is its OWN: its own aperture, its own world scale, its own
  quality. That is deliberate rather than a shortcut — the aperture you want for a photograph
  and the aperture you want to walk around behind are different numbers, and sharing them would
  mean every shot re-tuned the world and every walk re-tuned the camera. Its focus is separate
  too: a plain centre ray, throttled and eased in dioptres, that never touches the camera's own
  focus ring (which `AutoFocus` owns and which only moves while the viewfinder is up — otherwise
  you would raise the camera to find the ring somewhere you never put it).
  <p>Focal length is not a setting. It is read off the projection the frame was actually
  rendered with: whatever field of view the game is drawing at IS a focal length on a given
  frame size, so `f = halfFrameHeight * proj[1][1]`. That tracks the player's own FOV slider,
  sprinting and a spyglass for free, and it puts Minecraft's default 70-degree view at 17 mm —
  which is genuinely what that field of view is on full frame.
  <p>Depth of field only. Exposure, white balance, the tone curve and the dynamic-range curve
  belong to the photograph and are skipped; so are distortion and chromatic aberration, which
  are properties of the lens the CAMERA is carrying — bowing and fringing the view someone is
  playing through is a different proposition from doing it to a picture they chose to take.
  Focus peaking is skipped too: there is no focus ring here to aid.
  <p>The camera and the ambient lens never run at once — raising the viewfinder, taking a
  photo or recording all hand the optics back to the camera.
  <p>A permanent effect is paid for on every frame forever, so the gather's tap budget can now
  ramp DOWN as well as up: PERF takes 32 taps, BALANCED 80, HIGH the same 128 the viewfinder
  uses. It defaults to f/5.6 rather than to a photographer's f/2.8 for the same reason: at the
  wider aperture, looking down at the ground two blocks ahead threw terrain at 20 blocks to
  about 5.8 px and the player's own feet to 7.6 px, which reads as an effect rather than as
  depth. f/5.6 lands those on 2.9 and 3.8. Open it up if you want the world thrown out.
  <p>The held item stays sharp, which needs two separate tests because it reaches the frame two
  ways. Vanilla draws it at the end of renderWorld, after the depth copy and after clearing the
  depth buffer, so it leaves no mark in the copy — comparing the live depth against the copy
  finds it without caring about draw order. Iris disables vanilla's hand rendering outright and
  draws it from its own HandRenderer inside the level render, where it IS in the copy, at a
  reserved depth band (its projection is multiplied by scale(1,1,0.125), which lands the item
  between 0.09 and 0.11 blocks — nearer than the player's own bounding box lets any block face
  get). Gather taps landing on the item are dropped too, or its colour bleeds out into the blur
  around it as a faint aura.

## 1.2.1

### Fixed

- **Focus peaking carpeted the frame, and made the viewfinder read warmer than the photo.**
  Its edge test was a two-tap FORWARD difference against a 0.06 threshold, which is not an edge
  detector in Minecraft: block textures are dithered per texel, so neighbouring screen pixels
  clear 0.06 on a flat wall of grass. Every pixel that passed was replaced outright with
  PeakColor, a warm red-orange — and since peaking is viewfinder-only by design, that warmth
  appeared in the finder and never in the photograph it was previewing. Measured over real
  captures: on a detailed scene the old test painted 8.6-9.6% of the WHOLE frame (more within
  the in-focus band it is restricted to), lifting the frame's mean red from 0.147 to 0.209 and
  its red-to-blue ratio from 9.2 to 12.6. Now central differences — symmetric, so one dithered
  texel no longer registers as a slope — at a 0.14 threshold, followed by non-maximum
  suppression so only the RIDGE of an edge is marked. That is the step that turns a filled
  region into the one-pixel outline a real body draws: the same scenes now come out at 2.2-2.5%
  coverage with the mean red lifted 9% instead of 42%.
- **The camera screen's rows overlapped on a short window, and the headings went under
  them.** To fit nineteen settings the row PITCH stepped down from 22 pixels to as little as
  16 while the buttons stayed 20 tall, so each row ate 4 pixels of the one below it and the
  group headings were buried under the row that followed. That was the whole of why the screen
  looked cramped: it was not dense, it was overlapping. Squeezing is gone — the pitch is fixed
  at something readable and the list scrolls with the wheel instead. Scrolling moves by whole
  rows and a row that would be half-cut is simply not drawn, because Minecraft's widgets do not
  clip to a parent and a partly-placed row would paint straight over the header and the buttons
  below. Row width follows the window now too: the longest label and value together ("Dynamic
  Range Width: 8 stops", and more so in Japanese) ran off the end of a fixed 130-pixel row.

## 1.2.0

### Added

- **The viewfinder shows the exposure.** Not a preview of it — the exposure multiply itself,
  moved out of the CPU pass over the saved photo and into the shader, along with the tone curve
  and the highlight rolloff that used to sit beside it. Because the finder and the capture are
  then the same arithmetic on the same buffer the screenshot is read back from, they cannot
  drift apart; the earlier attempt at this was a flat alpha wash laid over the whole frame,
  which was never the exposure and diverged from the photograph exactly as you would expect a
  separate approximation to. Video recording picks all three up for free, having gone through
  the same pass all along. A DNG still skips the lot.
  <p>The multiply is also in LINEAR light now rather than on gamma-encoded bytes, which is what
  a sensor gain actually is — the same class of error the white-balance fix above corrected.
  Measured against the old chain: at neutral exposure the two agree to within 2 levels out of
  255 across the whole range, so the default look is unchanged, but a stop of compensation now
  behaves like a stop. +1 EV on a mid grey lands on 192 where it used to slam to 234 and jam
  against the rolloff, and -1 EV lands on 102 rather than 68.
  <p>One honest consequence: during a LONG exposure the shader runs per accumulated sample, so
  the exposure and the curves are applied before the samples are averaged rather than after. A
  real sensor integrates first and clips once. It only shows where the gain pushes a sample past
  full scale, which needs the shot to be badly over-exposed already — at the gain any auto mode
  or deliberate long exposure lands on, nothing clips and the two orders agree.
- **ND filter** — ND2 through ND1000, 1 to 10 stops. A piece of dark glass in front of the
  lens, and the only accessory in photography whose whole purpose is to make the picture
  worse-lit on purpose: it buys a wide aperture in daylight, and a long shutter in daylight.
  Purely multiplicative on the light, so it needs no model of its own — it is exactly that many
  stops off whatever the settings would otherwise deliver. In Manual that darkens the
  photograph, which is the honest result of fitting one and not compensating; in Av/Tv/P the
  camera compensates, and every ND stop deliberately goes to the shutter or the aperture and
  never to ISO, because answering an ND filter with ISO cancels the only reason to fit one.
  ND1000 on a daylight scene at f/5.6 lands on a 17-second exposure, which is exactly what the
  long-exposure accumulation is there to render. The viewfinder darkens with it, the way a real
  body's does — so a 10-stop filter in Manual really does leave you composing in the dark.
- **The camera screen is grouped.** Nineteen settings across two columns had become an
  undifferentiated list, so they now sit under headings — EXPOSURE and LENS on the left, IMAGE,
  VIEWFINDER, OUTPUT and FREECAM on the right — with a rule running out either side of each.
- **White balance.** AWB, or a fixed colour temperature from 2500 K to 12000 K. The correction
  is the ratio between daylight and whatever temperature the camera is told to assume, so
  5600 K is exactly the unmodified image and every other setting removes (or fails
  to remove) a cast that Minecraft's renderer already put there — torchlight really is
  rendered orange and night really is rendered blue, which is precisely what a sensor would
  have recorded. Dial daylight in a torch-lit cave and it stays orange, the same way it would
  on a real body. AWB does not guess from a table of real-world temperatures: it computes the
  illuminant from vanilla's own lighting maths — the block-light cubic out of `lightmap.fsh`,
  which is warm and dim far from a torch and white right at one, and the sky-light mix that
  goes blue after sunset — weighs the two by how much each is contributing at the metered
  points, and converts the result back to a correlated colour temperature. It corrects to 70%
  rather than fully, because every real camera's AWB under-corrects warm light on purpose: a
  candlelit room photographed to a perfectly neutral grey no longer reads as candlelit. Held
  to the same range the manual dial covers, since Minecraft's night sky is bluer than any
  temperature can be and an unclamped reading would ask for a correction no real body offers.
- **Sensor format** — medium format, full frame, APS-C, Micro Four Thirds or 1 inch, as one
  crop factor feeding both the field of view and the depth-of-field maths. Because it is the
  same number in both, they move together the way they really do between bodies: the same
  50 mm on APS-C frames like a 75 mm and separates the background like the 50 mm it still is,
  so matching the framing by stepping back is what actually costs the blur. The 3:2 frame
  shape is left alone — real MFT and medium-format bodies are 4:3, but frame proportions have
  nothing to do with the optics this setting exists to move.
- **Lateral chromatic aberration.** The coloured fringing that grows toward the corners
  because a lens magnifies short wavelengths slightly differently from long ones — red a touch
  larger, blue a touch smaller, green where it is, so the displacement is zero dead centre and
  grows with distance from it on its own. Derived from focal length exactly the way the
  existing barrel/pincushion distortion is, and for the same reason: both are failures to hold
  a wide field together. Roughly 6 px at the corner of a 1080p frame on an 8 mm, 2 px on a
  24 mm, half a pixel by 200 mm. Applied before focus peaking, since it happens to the light
  on its way to the sensor rather than to the finder overlay drawn over the result.
  Toggleable, because every modern body and raw developer corrects it automatically —
  switching it off is the body's lens correction being on, not a cheat.
- **Focus breathing.** The field of view narrowing slightly as the lens focuses closer. Not an
  effect layered over the optics but a consequence of them: focusing moves the image plane from
  f out to fS/(S-f), and the angle of view is set by the frame's half-height over *that*
  distance, not over the focal length — so there is no strength to tune. A 50 mm focused five
  blocks away frames about 2.5% tighter than at infinity, and at close focus it is dramatic.
  Toggleable, because "breathing-free" is what cine lenses are sold on and modern bodies ship
  focus-breathing compensation for video: a shot whose framing has to hold while the focus
  racks is a real reason to want it gone.
- **Colour noise at high ISO.** The ISO grain pass added one identical value to all three
  channels, which is a purely luminance grain — what film looks like, not what a digital sensor
  at high ISO looks like. A real sensor's colour sites are read out independently and then
  demosaiced, so their errors do not agree, and the colour component grows into the dominant
  one as the gain climbs. Now modelled as a shared luminance term plus per-channel colour
  terms arranged to cancel in luminance, with the colour share ramping from 15% of the noise at
  base ISO to 80% at the top. The colour component is also generated COARSE — one value per
  3-pixel square, bilinearly interpolated — rather than per pixel, because the two components
  do not live at the same spatial scale: luminance noise is per-site shot and read noise, while
  colour noise only exists after demosaicing, and that shared arithmetic correlates the error
  across neighbours. Per-pixel colour noise has the right amplitude and entirely the wrong
  scale, reading as a fine rainbow shimmer instead of the blotches a high-ISO frame actually
  has. The viewfinder's grain preview follows suit, drawing its coloured specks as small blocks.
- **EXIF.** Shutter speed, aperture, ISO, focal length (and its 35 mm equivalent for the
  chosen sensor), exposure program, metering mode, lens and capture time, written into saved
  JPEGs as an APP1 segment and into DNGs as a proper Exif sub-IFD. Values come from the same
  continuous targets the exposure maths uses rather than the readout's nearest marked stop, so
  an Av/Tv/P shot records the exposure it was actually given. DNGs additionally carry
  `AsShotNeutral`: white balance is left OUT of a raw file's pixels and recorded as metadata
  instead — which is the whole point of a raw file, since the developer can then move it
  anywhere with no loss, nothing having been multiplied away. PNG carries the same block in
  PNG's own `eXIf` chunk, spliced in after IHDR.

- **Scroll wheel changes a keyframe's focal length while re-aiming it.** While editing a
  camera-path keyframe's orientation (left+right click held together, possessing that
  keyframe's own viewpoint), the wheel used to do nothing — it only ever moved a keyframe's
  depth while just dragging its position. Now, while re-aiming, it steps the keyframe's own
  focal length instead, live in the possessed view, so a dolly-zoom shot can be dialled in
  from exactly the picture it will actually take. Releasing the edit restores whatever lens
  was set before.

### Fixed

- **White balance was a notch too cool at every setting, and clipped blue at the bottom.**
  The identity — the dial reading that applies no correction — sat at 6500 K, but a camera's
  Kelvin dial is labelled by the SCENE illuminant, and its daylight setting is by definition the
  one that renders daylight neutral on a D65 display. Anchored at D65 instead of at daylight,
  asking for plain daylight already produced a blue picture before any deliberate cast, and the
  low end ran so far past the blue channel's headroom that a mid grey came out 47,87,255 — a
  pinned channel and a twisted hue rather than a colour cast. The identity is daylight now.
  Two other things were wrong underneath it: the white points came from a piecewise fit to a
  blackbody's on-screen APPEARANCE, which is the wrong thing to take a ratio of because a
  channel gain is a linear quantity and the two disagree by exactly the transfer function
  (the Planckian locus through XYZ replaces it), and the gain was multiplied into the
  gamma-encoded framebuffer rather than into linear light, which is not what a sensor gain does.
  The cap now eases the whole gain vector back toward neutral instead of clipping the tallest
  channel, so a correction that runs past what a diagonal multiply can express desaturates
  rather than blowing out. Measured after: daylight AWB asks for 1.000/1.000/1.000, and 2500 K
  takes that mid grey to 102,122,210 with nothing pinned. The dial now stops at 2500 K, where
  real bodies stop, for the same reason.
- **The metadata panel sat on top of the photograph.** It gets a column of its own on the left
  now and the picture takes what is left. A 3:2 frame in a 16:9 window already has margin on
  both sides, so on a normal window this costs the image nothing — it only moves where the
  letterboxing sits. Nothing is reserved at all when a file carries no metadata.
- **The roll shows JPGs and DNGs, and reads back what a shot was taken at.** Every JPG in the
  camera roll had been failing to load with "Bad PNG Signature": `NativeImage.read` validates
  the PNG signature and decodes nothing else, so it was never going to open one. JPEG now goes
  through ImageIO instead (a pure decoder, unaffected by Minecraft's headless AWT, the same
  distinction the clipboard code already relies on), and DNG — which was excluded from the roll
  entirely, on the assumption there was no way to show it — is read back by the code that wrote
  it, rendered "as shot" by applying its own `BaselineExposure` and `AsShotNeutral` the way a
  raw developer's default rendering does. Opening the viewer now also shows the shutter,
  aperture, ISO, focal length, mode and capture time read out of the file itself, not from the
  current camera state, which has almost certainly moved since. PNGs carry that metadata too
  now, in PNG's own `eXIf` chunk: verified against stb — the decoder Minecraft actually uses —
  that a spliced PNG still decodes with byte-identical pixels, since an unknown ancillary chunk
  is one every decoder is required to skip.
- **The camera screen's Roll and Freecam buttons stay put.** They used to trail the last
  settings row, so every setting added pushed them further down — buried among the rows, and at
  a large GUI scale off the bottom edge entirely. They are anchored to the bottom of the screen
  now, with the settings centred in the space above, so they stay in the same findable place
  however long the list grows.

## 1.1.1

### Added

- **Focus peaking.** A camera-settings toggle highlights high-contrast edges sitting within a
  narrow band of the focus distance — the same aid a real mirrorless body draws over manual
  focus. Viewfinder only: never baked into a saved photo or a recorded frame. A separate,
  self-contained pass rather than woven into the depth-of-field shader, so it can't perturb
  the physically-modelled blur that pass exists for.

### Fixed

- **Long-exposure motion blur held together better.** Past about 2 seconds the per-sample
  interval — not the 8 ms floor below it — set the gap between accumulated samples, and the
  motion smear that covers that gap is exact only for perfectly linear motion; an orbit's arc
  or a hand's wobble could leave a hairline seam where consecutive samples' trails were
  supposed to meet, reading as choppiness rather than a continuous trail. The smear itself now
  overshoots its measured gap by 35%, buying every consecutive sample's trail a small overlap
  instead of an exact hand-off — favouring a continuous trail over a geometrically exact one,
  since the exact one is what read as choppy. Sample density was also raised, but for free
  rather than by doubling the real screenshot-and-shader cost: each real sample now also folds
  in a cheap CPU blend against the previous real sample as a virtual mid-sample, doubling the
  effective density at the cost of one extra same-size CPU pass with no additional GPU work —
  real captures stayed at the original 120.

## 1.1.0

### Added

- **Filenames and the mod's own version-compatibility declaration now state the actual
  covered range**, not just the single version each jar happens to be built against —
  `snapmatica-1.1.0+1.21-1.21.1.jar`, `+1.21.2-1.21.3.jar`, `+1.21.4.jar`, `+1.21.11.jar`.
  It used to just be whatever `mcVersion` the jar was compiled with, so a 1.21 or 1.21.2
  player had no way to tell from the filename that the 1.21.1 or 1.21.3 jar was theirs — and
  Fabric Loader's own compatibility check (`fabric.mod.json`'s `minecraft` field) was
  narrower than the code actually supports for exactly the same reason, `~1.21.1` rather
  than the true `>=1.21 <1.21.2`, which would have refused to load on plain 1.21 even though
  nothing about the jar cares.
- **Supports 1.21.10**, including freecam. Its rendering pipeline already matches 1.21.11's
  throughout — GPU texture handles, screenshot capture, the GUI widget/button API, mouse
  input — except `Camera.update()`, whose signature only changes at 1.21.11 itself. Freecam
  turned out not to need anything from that one method beyond a hook to cancel and override,
  and the older signature (`BlockView` instead of `World`, otherwise identical) still gives
  it one — so the same freecam, unchanged, now runs on 1.21.1 through 1.21.4 too, not just
  1.21.10. (1.21.8 remains a real port, not a version bump — its GPU texture pipeline touches
  six separate call sites differently — and is left for later.)
- **Supports 1.21.3.** Its API already matches 1.21.4's rather than 1.21.1's — the branch
  point some version-specific code was written against was one release later than the real
  one — so this also fixes those spots for anyone still on 1.21.2 or 1.21.3 who was silently
  getting 1.21.1-branch code that didn't actually match what their game exposed. (1.21.8 was
  looked at too — a rendering-pipeline overhaul there touches six separate call sites,
  screenshot capture among them, so it's a real port rather than a version bump and is being
  left for later.)
- **Freecam.** From the camera settings screen (G), fly the camera free of the player to
  frame a shot from anywhere — including with the player themself in it, as a stable
  subject. No key of its own: the settings screen already had a spare button (a dedicated
  Close is redundant with Esc), and this mod asks for enough of the keyboard as it is.
  Movement is WASD + Space/Shift, Ctrl to move faster, mouse to look; the player freezes in
  place and stays fully rendered while it's active, and is a valid autofocus subject in its
  own right — a selfie is exactly a subject in front of a lens the player isn't holding.
  It's a photography tool rather than a bare fly-cam: the viewfinder frame, EVF preview,
  focal-length zoom, autofocus and hotbar/held-item hiding all treat freecam exactly like
  the sneak-to-compose pose, so a shot framed in freecam looks and behaves like any other.
  Left/right click are blocked while it's active — an attack or block placement from a
  frozen body facing an unrelated direction has nothing to do with what's on screen. Taking
  damage hands control back immediately, so being flown away from the body can never turn
  into being stuck defenseless while something attacks it. Gravity, momentum and collision
  are left completely alone — an earlier version also pinned the player's position and
  velocity every tick to stop any drift at all, which was flight for free in survival if
  freecam happened to switch on mid-air; only the WASD/jump/sneak input is blanked now, so a
  player on the ground stays put the same way standing still always did, and a player
  already falling keeps falling. Works on every version this mod supports (1.21.1 through
  1.21.11) — the freecam hook it needs is one of the few things that stayed the same shape
  the whole way. The underlying refactor — reading the actual render camera instead of
  assuming it always sits at the player's eye — is also what would make a future drone-mod
  integration (Replay Mod, Flashback, freecam-type mods) automatic rather than a rewrite.
- **Drone mode**, a second control feel for freecam aimed at aerial footage rather than
  precise placement — a toggle on the camera settings screen, since it changes how flying
  feels enough that it shouldn't be the only option. Movement accelerates and drifts to a
  stop instead of snapping to speed, the vertical axis holds its altitude rather than
  coasting once Space/Shift are released, and X drops a pin on whatever is under the
  reticle — a living entity in a narrow cone if one is closer than the terrain behind it,
  otherwise the terrain itself — turning WASD/Space/Shift into an orbit around it (angle,
  radius, height) with the camera always facing the pin. X again releases it back to free
  flight from wherever the orbit left off. A pin on an entity tracks it as it moves; one on
  terrain holds still.
- **The targeted-block outline no longer shows in the viewfinder.** It was already hidden
  from the saved photo and recorded footage; a black wireframe box sitting in the middle of
  the frame while composing a shot was the same problem one render earlier.
- **Hide Player**, another camera-settings toggle for freecam: keeps the player's own frozen
  body out of the shot entirely, for landscape or wildlife footage it has no business
  standing in. Reuses the same vanilla rule that normally hides your own body in first
  person — freecam usually disables that rule (so the body shows once the camera has flown
  away), and this switches it back on instead.
- **World scale is a setting.** A Minecraft block has never had a real-world size, and the
  lens needs one to know how much anything defocuses — it used to be a fixed 37.5 cm. The
  camera screen now has a Scale row, from 1 cm a block (the same optics an actual diorama
  lens has, for a tilt-shift miniature look) up to 2 m. Saved with the rest of the camera's
  settings.
- **The viewfinder's distance readout shows blocks and metres together**, e.g. `10blk
  (3.8m)`. It used to show only metres and label it "m" without the scale conversion —
  correct only by coincidence at the old fixed scale, and wrong by whatever factor the
  scale differs from 37.5 cm once it became a setting. Blocks are what the focus ring is
  actually marked in; metres are what the depth of field is computed from.

### Fixed

- **A dramatically scaled-down world no longer grains where it should smoothly blur.**
  Circle of confusion is physically a fraction of the sensor, and it saturates toward its
  maximum quickly once a subject is more than a couple of multiples of the focus distance
  away — normal at any scale, but at 1 cm a block that maximum is reached within a few
  blocks instead of a few hundred, so the transition band where a receding surface is
  "still sharpening" sits in ordinary midground rather than off in an extreme foreground.
  The gather's sample count now ramps with how wide a circle the frame actually needs,
  measured to roughly halve the residual grain in that band; an ordinary shot's blur
  ceiling never nears the threshold that starts the ramp, so it costs nothing there.
- **The photograph itself can afford more than the viewfinder ever could.** A live preview
  is redrawn dozens of times a second and has to stay cheap everywhere; a fast-shutter
  photo is exactly one frame, and can spend what a real shutter does — on a heavily
  scaled-down world, roughly a tenth of a second more, for a visibly cleaner result.
  Applies only to that single frame, never to one of a long exposure's accumulated
  samples, which already converge by being averaged together.
- Found and worked around, in the course of the above: a deeply nested `clamp(mix(clamp(
  ...)))` expression measured six times worse on this GPU's own legacy GLSL compiler than
  the identical arithmetic written as two named locals — the same NVIDIA Cg-based path
  every measurement in this shader is taken on. Broken apart, not just here.
- Removed the handheld-shake warning from the viewfinder. It checked shutter speed against
  a simulated hand-shake threshold snapmatica never actually modelled, so it was warning
  about an effect that could not occur.

## 1.0.2

### Fixed

- **Distant terrain is no longer blurred by a rule of its own.** Anything past a few hundred
  blocks was given a floor of 5 px of blur, ramped in between 200 and 600 blocks. The amount
  came from a constant rather than from the lens, so it did not move when the aperture did:
  stopped down to f/22, where the circle of confusion is 1.4 px and the whole scene should be
  sharp, the far field still came through as mush. It is gone. The far field now follows the
  same thin-lens formula as everything else — sharp at f/22, softened at f/1.4 by as much as
  the optics actually call for.
- **Racking out to infinity no longer makes the distance snap into focus.** That floor faded
  out as the focus approached its far stop, so it was tied to where the lens was pointed
  rather than to the subject: the horizon changed while nothing in the scene did, which was
  plain to see in video. Without it the far field is one continuous function of depth.

This was only visible from 1.0.1 onward. The floor was applied to a pixel as itself but not
as anybody's neighbour, so no sample ever cleared the defocus's membership test and it did
nothing at all; making that consistent — correct in itself — switched on a feature that had
been dormant since it was written.

## 1.0.1

### Fixed

- **Foreground defocus dissolves.** Foliage or a fence held close to a fast lens now washes
  together into a haze with no findable edge, instead of softening while keeping its
  outline and the gaps between its leaves readable. A leaf a metre from the lens spreads
  over a disc hundreds of times its own area, so it covers only a few per cent of the
  aperture and belongs on screen at a few per cent — the defocus was computing that
  coverage correctly all along and then dividing it back out of the result, which restored
  the foreground to full opacity everywhere its own silhouette sat over a sharp background.
  Backgrounds, bokeh and anything in focus are untouched, to the pixel.
- **Very heavy foreground defocus no longer keeps the shape of what made it.** The blur
  radius was capped at 120 px whatever the optics asked for, and once every foreground pixel
  is pinned to the same radius the result is just its silhouette dilated by that radius —
  a scaled copy of the shape, still opaque through the middle of anything wider. Leaves
  right at the lens with the focus 20 blocks out came through seven times too dense and
  stopped dead at a hard edge. The ceiling is now three quarters of the frame height, so
  the lens decides, and it is a fraction of the frame rather than a pixel count because a
  circle of confusion is a fraction of the sensor — the old ceiling bit harder the higher
  the resolution.
- **No more dark fringe clinging to a foreground held against the sky.** The atmospheric
  haze floor on distant geometry was applied to a pixel when it was the one being drawn but
  not when it was somebody's neighbour, so a hazed sky reached nothing — not even itself —
  and the defocus renormalised against a background it had counted as barely there. A dark
  post against bright sky came out over twice as opaque as the lens gives, twelve pixels
  outside its own edge.
- **The defocus spends its samples where the blur actually is.** The tap count now follows
  the area being gathered instead of being fixed, which more than pays for the fix above: a
  landscape frame, where the haze floor puts a small disc on nearly every pixel, is now
  faster than it was in 1.0.0.
- **The residue left by a wide gather is smoothed at the scale it actually has.** It was
  sized off the pixel's own defocus, which in a foreground's haze is not the blur doing the
  damage — the wall behind a fence is barely soft itself, so 3 px of smoothing was being
  asked for against blotches ten times that. It now follows the blur blooming over the
  pixel, and reaches three times as far for a ninth of the taps.

## 1.0.0

Snapmatica's first stable release. The lens is now built on real optics rather than
approximations of them, and there's a camera roll to look at what you shot.

Supports **1.21.1, 1.21.4, 1.21.11 and 26.1.2**.

### The lens is a lens now

- **Aperture is a physical opening.** The ring sets the diameter of the blades, not the
  f-number. Since N = f/D, zooming while the blades stay put moves the f-number on its
  own — exactly why a kit zoom is "f/3.5-5.6". Clamped to what the barrel can reach.
- **Diffraction.** Stopping down past about f/11 softens the whole frame, from the Airy
  disc for the current f-number, added in quadrature with the circle of confusion.
- **Distortion.** Wide angles barrel, long ones pincushion, and 50mm is neutral.
- **Long exposure.** Shutter speeds slower than 1/30 accumulate multiple frames into one
  photograph — light trails, smoothed water, the lot. ISO now goes down to 25 for it.
- **Motion blur** from camera movement during the exposure, as a continuous smear rather
  than a stack of ghosts.
- **Focus racks smoothly**, in dioptres, so pulling from 5m to infinity is one continuous
  move instead of a snap. Manual focus uses the same rack as autofocus.
- **Focus reaches through glass.** Point at a window and the camera focuses on what's
  beyond it, and the view through it stays sharp.

### Camera roll

Press **G**, then **Camera Roll** — a phone-style grid of every photo and clip, newest
first. Click to view full screen, arrow keys to move through. Copy to clipboard, show in
folder, or open in your desktop viewer. Videos show a poster frame and play externally.

### Fixed

- Infinity focus really is infinity; distant terrain no longer blurs.
- Viewfinder framing matches the photograph. It used to show less than it recorded, which
  also meant the focal length displayed a narrower angle than it delivered.
- Video no longer drops frames, and records at the frame rate you selected.
- Your hand no longer appears in footage when shaders are installed.
- Every message has an English translation.

### Known limitations

- **A dissolving foreground softens the background behind its own silhouette.** One
  rendered frame holds no record of what a leaf was covering, so where the leaf used to be
  the scene is reconstructed from what surrounds it. Its colour and brightness match to
  within half a level out of 255 — but its fine detail cannot be recovered, so a sharp
  backdrop reads a little soft in a band the width of the foreground itself. A backdrop
  that is itself defocused shows nothing at all.
- Terrain drawn by LOD mods (Voxy, Distant Horizons) leaves no depth for the camera to
  read, so autofocus cannot lock onto it. Focus falls back to infinity, which renders
  distant terrain sharp — the right answer in practice.
- Longitudinal chromatic aberration and signal-dependent noise are not implemented yet.
