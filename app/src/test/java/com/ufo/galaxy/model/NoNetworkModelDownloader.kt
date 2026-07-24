package com.ufo.galaxy.model

import java.io.File
import java.io.IOException

/**
 * 单元测试专用 [ModelDownloader]:HttpFactory 一律抛 [IOException],任何下载请求
 * 立即失败(LoopController.ensureModels 按设计"下载失败不阻塞主循环",继续执行)。
 *
 * 背景(真教训,CI 实测):默认 DefaultHttpFactory 会向 HuggingFace 发真实网络请求。
 * "空模型目录 + 真下载器"的测试夹具一旦跑进 ensureModels,就会在 CI Runner 里真拉
 * 数百 MB~GB 级模型文件。此前该路径被 posture 门(control_only 直接拒绝)意外挡住,
 * posture 修复(本地路由改传 join_runtime)解锁后,gate 6/7 全量测试因多轮真实模型
 * 下载从 16.5 分钟膨胀超过 20 分钟 timeout 被杀。单元测试构造 LoopController 时
 * 一律使用本工厂,禁止触网。
 */
fun noNetworkModelDownloader(modelsDir: File): ModelDownloader =
    ModelDownloader(
        modelsDir = modelsDir,
        httpFactory = ModelDownloader.HttpFactory {
            throw IOException("unit tests must not download models over the network")
        }
    )
