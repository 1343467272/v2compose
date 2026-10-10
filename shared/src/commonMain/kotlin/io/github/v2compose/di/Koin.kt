package io.github.v2compose.di

import io.github.v2compose.V2AppViewModel
import io.github.v2compose.core.StringDecoder
import io.github.v2compose.core.UrlStringDecoder
import io.github.v2compose.datasource.AccountPreferences
import io.github.v2compose.datasource.AppPreferences
import io.github.v2compose.datasource.AppStateStore
import io.github.v2compose.datasource.MyFollowingPagingSource
import io.github.v2compose.datasource.MyTopicsPagingSource
import io.github.v2compose.network.DefaultSingBoxSubscriptionFetcher
import io.github.v2compose.network.GithubApi
import io.github.v2compose.network.KtorGithubApi
import io.github.v2compose.network.SingBoxSubscriptionFetcher
import io.github.v2compose.network.V2exApi
import io.github.v2compose.repository.AccountRepository
import io.github.v2compose.repository.AppRepository
import io.github.v2compose.repository.NewsRepository
import io.github.v2compose.repository.NodeRepository
import io.github.v2compose.repository.TopicRepository
import io.github.v2compose.repository.UserRepository
import io.github.v2compose.repository.def.DefaultAccountRepository
import io.github.v2compose.repository.def.DefaultAppRepository
import io.github.v2compose.repository.def.DefaultNewsRepository
import io.github.v2compose.repository.def.DefaultNodeRepository
import io.github.v2compose.repository.def.DefaultTopicRepository
import io.github.v2compose.repository.def.DefaultUserRepository
import io.github.v2compose.shared.core.V2EventManager
import io.github.v2compose.ui.gallery.GalleryViewModel
import io.github.v2compose.ui.login.LoginViewModel
import io.github.v2compose.ui.login.google.GoogleLoginViewModel
import io.github.v2compose.ui.login.twostep.TwoStepLoginViewModel
import io.github.v2compose.ui.main.MainViewModel
import io.github.v2compose.ui.main.home.recent.RecentViewModel
import io.github.v2compose.ui.main.home.tab.NewsViewModel
import io.github.v2compose.ui.main.mine.MineViewModel
import io.github.v2compose.ui.main.mine.following.MyFollowingViewModel
import io.github.v2compose.ui.main.mine.nodes.MyNodesViewModel
import io.github.v2compose.ui.main.mine.topics.MyTopicsViewModel
import io.github.v2compose.ui.main.nodes.NodesViewModel
import io.github.v2compose.ui.main.notifications.NotificationViewModel
import io.github.v2compose.ui.node.NodeViewModel
import io.github.v2compose.ui.search.SearchViewModel
import io.github.v2compose.ui.settings.SettingsScreenState
import io.github.v2compose.ui.settings.SettingsViewModel
import io.github.v2compose.ui.supplement.AddSupplementViewModel
import io.github.v2compose.ui.topic.TopicViewModel
import io.github.v2compose.ui.user.UserViewModel
import io.github.v2compose.ui.webview.WebViewViewModel
import io.github.v2compose.ui.write.WriteTopicViewModel
import io.github.v2compose.usecase.CheckForUpdatesUseCase
import io.github.v2compose.usecase.CheckInUseCase
import io.github.v2compose.usecase.DefaultExternalImageUrlResolver
import io.github.v2compose.usecase.ExternalImageUrlResolver
import io.github.v2compose.usecase.LoadNodesUseCase
import io.github.v2compose.usecase.RefreshSingBoxSubscriptionUseCase
import io.github.v2compose.usecase.UpdateAccountUseCase
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val sharedCoreModule = module {
    singleOf(::V2EventManager)
    singleOf(::UrlStringDecoder)
    single<StringDecoder> { get<UrlStringDecoder>() }
}

val sharedNetworkModule = module {
    singleOf(::V2exApi)
    single<GithubApi> { KtorGithubApi(get()) }
    single<SingBoxSubscriptionFetcher> { DefaultSingBoxSubscriptionFetcher(get()) }
}

val sharedDataModule = module {
    singleOf(::DefaultAppRepository) { bind<AppRepository>() }
    singleOf(::DefaultNewsRepository) { bind<NewsRepository>() }
    singleOf(::DefaultNodeRepository) { bind<NodeRepository>() }
    singleOf(::DefaultTopicRepository) { bind<TopicRepository>() }
    singleOf(::DefaultUserRepository) { bind<UserRepository>() }
    single<AccountRepository> { DefaultAccountRepository(get(), get(), get(), get(), get()) }

    single { AppPreferences(get(named("App"))) }
    single { AccountPreferences(get(named("Account"))) }
    singleOf(::AppStateStore)
}

val sharedUseCaseModule = module {
    singleOf(::CheckForUpdatesUseCase)
    singleOf(::UpdateAccountUseCase)
    singleOf(::CheckInUseCase)
    singleOf(::LoadNodesUseCase)
    singleOf(::RefreshSingBoxSubscriptionUseCase)
    single<ExternalImageUrlResolver> { DefaultExternalImageUrlResolver(get()) }
}

val sharedPagingModule = module {
    factoryOf(::MyTopicsPagingSource)
    factoryOf(::MyFollowingPagingSource)
}

val sharedViewModelModule = module {
    viewModelOf(::V2AppViewModel)
    viewModelOf(::MainViewModel)
    viewModelOf(::RecentViewModel)
    viewModelOf(::NewsViewModel)
    viewModelOf(::NodesViewModel)
    viewModelOf(::NotificationViewModel)
    viewModelOf(::MineViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::GalleryViewModel)
    viewModelOf(::NodeViewModel)
    viewModelOf(::UserViewModel)
    viewModelOf(::TopicViewModel)

    viewModelOf(::LoginViewModel)
    viewModelOf(::TwoStepLoginViewModel)
    viewModelOf(::GoogleLoginViewModel)
    viewModelOf(::AddSupplementViewModel)
    viewModelOf(::MyTopicsViewModel)
    viewModelOf(::MyFollowingViewModel)
    viewModelOf(::MyNodesViewModel)
    viewModelOf(::WriteTopicViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::WebViewViewModel)

    singleOf(::SettingsScreenState)
}

fun sharedModules(): List<Module> = listOf(
    sharedCoreModule,
    sharedNetworkModule,
    sharedDataModule,
    sharedUseCaseModule,
    sharedPagingModule,
    sharedViewModelModule,
    platformModule,
)

/**
 * 跨平台 Koin 初始化入口
 */
fun initKoin(
    appDeclaration: KoinAppDeclaration = {},
    platformModules: List<Module> = emptyList(),
) {
    startKoin {
        appDeclaration()
        modules(sharedModules() + platformModules)
    }
}
