import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const TAG: string = 'Min';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    // 作为默认浏览器被拉起时，want.uri 就是外部传来的网址
    const uri: string = want.uri === undefined ? '' : want.uri;
    AppStorage.setOrCreate<string>('startUrl', uri);
    try {
      webview.WebCookieManager.putAcceptCookieEnabled(true);
    } catch (error) {
      const err = error as BusinessError;
      hilog.warn(0, TAG, 'enable cookie failed: %{public}s', err.message);
    }
    hilog.info(0, TAG, 'onCreate startUrl=%{public}s', uri);
  }

  onDestroy(): void {
    hilog.info(0, TAG, 'onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(0, TAG, 'loadContent failed: %{public}s', JSON.stringify(err) ?? '');
        return;
      }
      hilog.info(0, TAG, 'loadContent succeeded');
    });

    // 纯黑：窗口底色 + 状态栏 / 导航栏。
    // 窗口底色必须是黑的 —— 页面默认只铺满安全区，安全区之外（底部导航栏那条）
    // 露出的是窗口默认底色，不设就是白色，会出现一条白边。
    try {
      const win: window.Window = windowStage.getMainWindowSync();
      win.setWindowBackgroundColor('#000000');
      win.setWindowSystemBarProperties({
        statusBarColor: '#000000',
        statusBarContentColor: '#FFFFFF',
        navigationBarColor: '#000000',
        navigationBarContentColor: '#FFFFFF'
      }).then(() => {
        hilog.info(0, TAG, 'window painted black');
      }).catch((error: BusinessError) => {
        hilog.warn(0, TAG, 'setWindowSystemBarProperties failed: %{public}s', error.message);
      });
    } catch (error) {
      const err = error as BusinessError;
      hilog.warn(0, TAG, 'window setup failed: %{public}s', err.message);
    }
  }

  onWindowStageDestroy(): void {
    hilog.info(0, TAG, 'onWindowStageDestroy');
  }

  onForeground(): void {
    hilog.info(0, TAG, 'onForeground');
  }

  onBackground(): void {
    hilog.info(0, TAG, 'onBackground');
  }
}
