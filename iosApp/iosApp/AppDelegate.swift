import SwiftUI
import UIKit
import ComposeApp

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil) -> Bool {
        let res = IOSDelegate.shared.didFinishLaunching(application: application)
        SpeechAnalyzerBridge.register()
        UNUserNotificationCenter.current().delegate = self
        return res
    }
    
    func application(_ app: UIApplication,
                     open url: URL,
                     options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return IOSDelegate.shared.handleOpenUrl(url: url)
    }
    
    func applicationWillTerminate(_ application: UIApplication) {
        IOSDelegate.shared.applicationWillTerminate()
    }
    
    func applicationDidEnterBackground(_ application: UIApplication) {
        IOSDelegate.shared.applicationDidEnterBackground()
    }
    
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        IOSDelegate.shared.applicationDidRegisterForRemoteNotificationsWithDeviceToken(deviceToken: deviceToken)
    }
    
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        IOSDelegate.shared.applicationDidReceiveRemoteNotification(userInfo: userInfo, fetchCompletionHandler: { (result: KotlinULong) -> Void in
            completionHandler(UIBackgroundFetchResult(rawValue: result as! UInt) ?? .noData)
        })
    }

    func applicationDidReceiveMemoryWarning(_ application: UIApplication) {
        IOSDelegate.shared.applicationDidReceiveMemoryWarning()
    }
    
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.alert, .sound, .badge])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        IOSDelegate.shared.userNotificationCenterDidReceiveResponse(response: response, completionHandler: completionHandler)
    }
    
    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([any UIUserActivityRestoring]?) -> Void) -> Bool {
        return IOSDelegate.shared.applicationWillContinue(userActivity: userActivity)
    }
}
