package com.dsoftware.ghmanager.data

import com.dsoftware.ghmanager.api.model.GitHubWorkflowRun
import com.dsoftware.ghmanager.ui.settings.GhActionsSettingsService
import com.intellij.collaboration.async.CompletableFutureUtil.submitIOTask
import com.intellij.collaboration.async.CompletableFutureUtil.successOnEdt
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.GithubApiRequestExecutor
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.pullrequest.data.GHListLoader
import org.jetbrains.plugins.github.util.GitRemoteUrlCoordinates
import org.jetbrains.plugins.github.util.GithubUrlUtil
import org.jetbrains.plugins.github.util.LazyCancellableBackgroundProcessValue
import java.io.IOException
import java.util.concurrent.CompletableFuture

@Service
class WorkflowDataContextRepository {

    private val repositories =
        mutableMapOf<GHRepositoryCoordinates, LazyCancellableBackgroundProcessValue<WorkflowRunSelectionContext>>()

    @RequiresBackgroundThread
    @Throws(IOException::class)
    fun getContext(
        disposable: Disposable,
        account: GithubAccount,
        requestExecutor: GithubApiRequestExecutor,
        gitRemoteCoordinates: GitRemoteUrlCoordinates,
        settingsService: GhActionsSettingsService,
    ): WorkflowRunSelectionContext {
        LOG.debug("Get User and  repository")
        val fullPath = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(gitRemoteCoordinates.url)
            ?: throw IllegalArgumentException(
                "Invalid GitHub Repository URL - ${gitRemoteCoordinates.url} is not a GitHub repository"
            )
        val repositoryCoordinates = RepositoryCoordinates(account.server, fullPath)
        LOG.debug("Create WorkflowDataLoader")
        val singleRunDataLoader = SingleRunDataLoader(requestExecutor)
        requestExecutor.addListener(singleRunDataLoader) {
            singleRunDataLoader.invalidateAllData()
        }
        val listLoader = WorkflowRunListLoader(
            ProgressManager.getInstance(),
            requestExecutor,
            repositoryCoordinates,
            settingsService
        )

        return WorkflowRunSelectionContext(
            disposable,
            singleRunDataLoader,
            listLoader
        )
    }

    @RequiresEdt
    fun acquireContext(
        disposable: Disposable,
        repository: GHRepositoryCoordinates, remote: GitRemoteUrlCoordinates,
        account: GithubAccount, requestExecutor: GithubApiRequestExecutor,
        settingsService: GhActionsSettingsService,
    ): CompletableFuture<WorkflowRunSelectionContext> {
        return repositories.getOrPut(repository) {
            val contextDisposable = Disposer.newDisposable("contextDisposable")
            Disposer.register(disposable, contextDisposable)

            LazyCancellableBackgroundProcessValue.create { indicator ->
                ProgressManager.getInstance().submitIOTask(indicator) {
                    try {
                        getContext(contextDisposable, account, requestExecutor, remote, settingsService)
                    } catch (e: Exception) {
                        if (e !is ProcessCanceledException) LOG.warn("Error occurred while creating data context", e)
                        throw e
                    }
                }.successOnEdt { ctx ->
                    Disposer.register(contextDisposable, ctx)
                    ctx
                }
            }
        }.value
    }

    @RequiresEdt
    fun clearContext(repository: GHRepositoryCoordinates) {
        repositories.remove(repository)?.drop()
    }

    companion object {
        private val LOG = thisLogger()

        fun getInstance(project: Project) = project.service<WorkflowDataContextRepository>()
    }
}