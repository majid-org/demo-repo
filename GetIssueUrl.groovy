import com.exalate.api.domain.connection.IConnection

class GetIssueUrl {
    static <T> T await(scala.concurrent.Future<T> f) { scala.concurrent.Await$.MODULE$.result(f, scala.concurrent.duration.Duration$.MODULE$.Inf()) }
    static <T> T orNull(scala.Option<T> opt) { opt.isDefined() ? opt.get() : null }

    static String getLocal(String issueKey) {
        def injector = InjectorGetter.getInjector()
        /**
         * Due to changes on Exalate's API from 5.3 to 5.4 we need to consider that IJCloudGeneralSettingsRepository might have
         * a different classname such as IJCloudGneeralSettingsPersistence, so we will load the class dinamycally and catching an exception if Exalate is running
         * 5.3 or lower version
         */
        def getGeneralSettings = {
            def classLoader = this.getClassLoader()
            def gsp
            try {
                gsp = injector.instanceOf(classLoader.loadClass("com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsRepository"))
            } catch(ClassNotFoundException exception) {
                gsp = injector.instanceOf(classLoader.loadClass("com.exalate.api.persistence.issuetracker.jcloud.IJCloudGeneralSettingsPersistence"))
            }
            def gsOpt = await(gsp.get())
            def gs = orNull(gsOpt)
            gs
        }
        final def gs = getGeneralSettings()
        com.exalate.util.UrlUtils.concat(gs.issueTrackerUrl, "/browse/", issueKey)
    }

    static String getRemote(String remoteIssueKey, IConnection connection) {
        com.exalate.util.UrlUtils.concat(connection.remoteInstance.url.toString(), "/browse/", remoteIssueKey)
    }
}
