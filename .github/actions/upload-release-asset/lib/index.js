"use strict";
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (Object.hasOwnProperty.call(mod, k)) result[k] = mod[k];
    result["default"] = mod;
    return result;
};
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core = __importStar(require("@actions/core"));
const github_1 = require("@actions/github");
const glob_1 = __importDefault(require("glob"));
const fs_1 = require("fs");
const path = __importStar(require("path"));
const MediaTypes = __importStar(require("./media_types.json"));
class UpdateGitHubRelease {
    constructor() {
        this.octokit = new github_1.GitHub(core.getInput("github_token", { required: true }));
        this.tagName = core.getInput("tag_name", { required: true }).replace("refs/tags/", "");
        this.overwrite = core.getInput("overwrite") === "true";
    }
    async getDuplicateAssets(releaseId, targetAssetName) {
        const assets = (await this.octokit.repos.listAssetsForRelease(Object.assign(Object.assign({}, github_1.context.repo), { release_id: releaseId }))).data;
        const assetNameFilter = Array.isArray(targetAssetName)
            ? (assetName) => targetAssetName.includes(assetName)
            : (assetName) => assetName === targetAssetName;
        return assets.filter(({ name }) => assetNameFilter(name));
    }
    async deleteDuplicateAssets(releaseId, assetName) {
        const duplicateAssets = await this.getDuplicateAssets(releaseId, assetName);
        if (duplicateAssets.length) {
            if (this.overwrite) {
                await Promise.all(duplicateAssets.map(({ id, name }) => {
                    core.debug(`An asset called ${name} already exists in release ${this.tagName} so we'll overwrite it.`);
                    return this.octokit.repos.deleteReleaseAsset(Object.assign(Object.assign({}, github_1.context.repo), { asset_id: id }));
                }));
            }
            else {
                core.setFailed(`Assets called ${duplicateAssets.map(({ name }) => name).join(", ")} already exists.`);
                return;
            }
        }
        else {
            core.debug(`No pre-existing asset called ${Array.isArray(assetName) ? assetName.join(", ") : assetName} found in release ${this.tagName}.`);
        }
    }
    static getMIMEType(fileBytes) {
        var _a, _b;
        const magicNum = (_a = Object.keys(MediaTypes).find((magicNum) => magicNum === fileBytes.toString("hex", 0, magicNum.length / 2))) !== null && _a !== void 0 ? _a : "";
        return (_b = new Map(Object.entries(MediaTypes)).get(magicNum)) !== null && _b !== void 0 ? _b : "application/octet-stream";
    }
    async uploadReleaseAsset(uploadUrl, assetPath, assetName = path.basename(assetPath), assetContentType) {
        const file = await fs_1.promises.readFile(assetPath);
        core.debug(`Uploading ${file} to ${assetName} in release ${this.tagName}.`);
        return this.octokit.repos.uploadReleaseAsset({
            url: uploadUrl,
            name: assetName,
            data: file,
            headers: {
                "content-type": assetContentType !== null && assetContentType !== void 0 ? assetContentType : UpdateGitHubRelease.getMIMEType(file),
                "content-length": file.byteLength
            }
        });
    }
    async uploadToReleases({ id, upload_url }, files) {
        const assetPaths = [];
        const assetNames = [];
        for (const file of files) {
            if ((await fs_1.promises.stat(file)).isFile()) {
                assetPaths.push(file);
                assetNames.push(path.basename(file));
            }
            else {
                core.debug(`Skipping ${file}, since it's not a file`);
            }
        }
        if (!assetPaths.length) {
            core.debug("File not found.");
            return;
        }
        await this.deleteDuplicateAssets(id, assetNames);
        await Promise.all(assetPaths.map((assetPath, index) => this.uploadReleaseAsset(upload_url, assetPath, assetNames[index])));
    }
    async uploadToRelease({ id, upload_url }, file, assetName, assetContentType) {
        if (!(await fs_1.promises.stat(file)).isFile()) {
            core.debug(`Skipping ${file}, since its not a file`);
            return;
        }
        await this.deleteDuplicateAssets(id, assetName);
        await this.uploadReleaseAsset(upload_url, file, assetName, assetContentType);
    }
}
async function run() {
    try {
        const file = core.getInput("file", { required: true });
        const githubRelease = new UpdateGitHubRelease();
        const { octokit, tagName } = githubRelease;
        try {
            core.debug(`Get Release by tag ${tagName}.`);
            const { data: { id } } = await octokit.repos.getReleaseByTag(Object.assign(Object.assign({}, github_1.context.repo), { tag: tagName }));
            await octokit.repos.deleteRelease(Object.assign(Object.assign({}, github_1.context.repo), { release_id: id }));
        }
        catch (err) { /* continue regardless of error */ }
        const releaseInfo = (await octokit.repos.createRelease(Object.assign(Object.assign({}, github_1.context.repo), { tag_name: tagName, name: `${tagName}: ${github_1.context.payload.head_commit.message}`, target_commitish: github_1.context.sha, draft: core.getInput("draft") === "true", prerelease: core.getInput("prerelease") === "true" }))).data;
        const files = glob_1.default.sync(file);
        if (files.length) {
            if (files.length > 1) {
                await githubRelease.uploadToReleases(releaseInfo, files);
            }
            else {
                const assetName = core.getInput("asset_name", { required: true });
                const assetContentType = core.getInput("asset_content_type");
                await githubRelease.uploadToRelease(releaseInfo, files[0], assetName, assetContentType);
            }
        }
        else {
            core.setFailed("No files matching the glob pattern were found.");
        }
    }
    catch (error) {
        core.setFailed(error.message);
    }
}
run();
