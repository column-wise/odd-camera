# Odd Camera

실시간 GPU 필터를 얹은 Android 카메라 앱입니다. 미리보기, 사진, 동영상 녹화가 모두 같은 필터 렌더링 경로를 거치므로 화면에 보이는 그대로 저장됩니다.

## 기능

- 필터 (좌우 스와이프로 전환)
  - **None**: 필터 없음
  - **Light Boxes**: 밝은 영역을 박스로 추적해 표시. 사진은 셔터를 누른 순간 화면에 있던 박스를 그대로 저장
  - **ASCII v2**: 영상을 문자로 렌더링. 영역은 ML Kit 사람 분할("사람") 또는 상위 N% 밝은 부분("밝은 부분")으로 선택, 문자 종류(프리셋/직접 입력)와 가로 문자 수 조절
- 필터별 파라미터는 "조정" 패널에서 바꾸며, 값은 필터마다 DataStore에 저장되어 다음 실행에도 유지됩니다.
- 기본 카메라 기능: 사진(4:3) / 동영상(1080p 30fps, H.264 + AAC, 16:9), 플래시, 타이머, 전/후면 전환(전면은 미리보기와 동일하게 좌우 반전 저장), 1x/2x/5x 줌 및 핀치 줌, 탭 초점
- 저장 위치: `DCIM/OddCamera` (MediaStore). 화면은 세로 고정이며 결과물 방향은 촬영/녹화 시작 시점의 기기 방향을 따릅니다.
- 성능 단계(tier): 프레임 시간이 길어지면 렌더 해상도를 자동으로 낮추고, 인코더 설정도 기기에 맞게 폴백합니다.

## 빌드 / 설치

시스템 기본 Java가 8이면 빌드되지 않으므로 Gradle 실행 전에 JDK 17을 지정하세요.

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.14-zulu"   # JDK 17 경로로 변경
./gradlew :app:testDebugUnitTest      # JVM 단위 테스트
./gradlew :app:installDebug           # 연결된 기기에 설치
```

`local.properties`에 `sdk.dir=<Android SDK 경로>`가 필요합니다 (git 제외 대상). minSdk 29, targetSdk 36.

### 기기 테스트 (주의)

**`connectedDebugAndroidTest`는 사용하지 마세요.** 테스트 종료 후 앱을 제거(uninstall)하여 기기의 `DCIM/OddCamera` 사진/영상이 함께 삭제됩니다. 대신 설치 후 `am instrument`로 실행합니다. 화면은 잠금 해제 상태여야 합니다.

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w com.juhyun.oddcamera.test/androidx.test.runner.AndroidJUnitRunner
```

성능 로그는 `adb logcat -s OddPerf` (1초마다 `fps`, `avgMs`, `tier`, 녹화 중이면 `rec`).

## 필터 추가하기

1. `filter/` 아래에 `Filter` 인터페이스를 구현합니다: `id`, `title`, `params`(파라미터 정의), `analyze(frame, params, previous)`(저해상도 휘도 프레임 분석), `createRenderer()`(GL 스레드에서 호출되는 `FilterRenderer`).
2. 선택적 훅:
   - `needsRgba(params)`: 분석에 RGBA 프레임이 필요할 때 (ASCII 사람 모드)
   - `captureAnalysis(...)`: 사진 촬영 시 고해상도 스틸에 맞춰 분석을 다시 계산
   - `RotatableAnalysis`: 분석 결과가 기기 방향에 따라 회전되어야 할 때 구현
3. `filter/FilterRegistry.kt`의 목록에 한 줄을 추가합니다.

```kotlin
val all: List<Filter> = listOf(NoneFilter, LightBoxesFilter, AsciiFilter, MyFilter)
```

카메라/녹화/저장 코드는 수정할 필요가 없습니다. 파라미터는 프레임 폭 기준 정규화 값으로 정의하세요(해상도 독립).

## 프로젝트 구조

`app/src/main/kotlin/com/juhyun/oddcamera/`

- `camera/` CameraX 제어, 타이머
- `filter/` `Filter`/`FilterParam`/`FilterRegistry`, 파라미터 저장, 필터별 구현 (`none`, `boxes`, `ascii`)
- `render/` GL 렌더 스레드, 필터 패스, 분석 리더, 사진 렌더러, 성능 거버너
- `gl/` EGL/셰이더 유틸
- `record/` 동영상 인코더, 인코더 선택/폴백
- `capture/` 사진 촬영, MediaStore 저장
- `ui/` Compose 화면, ViewModel, 권한 게이트

## 알려진 한계

- 저사양 기기 검증 미완료(보류): 성능 측정은 Galaxy S26 Ultra 한 대에서만 했고, 두 번째 기기에서의 fps/성능 단계 하향/녹화 폴백 확인은 아직 하지 못했습니다.
- 가로 방향 촬영은 자동 테스트로만 검증했고, 실기기 육안 확인은 사용자 확인이 필요합니다.
- ASCII 사람 모드에서 정지 사진 분할은 촬영 시 미리보기가 최대 1.5초 멈출 수 있습니다.
- 미리보기와 녹화 모두 카메라 프레임레이트를 30fps로 고정합니다. 아주 어두운 장면에서는 노출 시간이 33ms로 제한되어 자동 노출이 15fps일 때보다 어둡거나 노이즈가 많아 보일 수 있습니다.
