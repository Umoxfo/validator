import * as core from "@actions/core";
import { GitHub, context } from "@actions/github";
import glob from "glob";
import { promises as fs } from "fs";
import * as path from "path";
import * as MediaTypes from "./media_types.json";

import type { Octokit } from "@octokit/rest";
type UploadReleaseAssetResponse = Octokit.Response<Octokit.ReposUploadReleaseAssetResponse>;

class UpdateGitHubRelease {
    readonly octokit = new GitHub(core.getInput("github_token", { required: true }));
    readonly tagName = core.getInput("tag_name", { required: true }).replace("refs/tags/", "");
    private readonly overwrite = core.getInput("overwrite") === "true";

    private async getDuplicateAssets(releaseId: number, targetAssetName: string | string[]): Promise<Octokit.ReposListAssetsForReleaseResponseItem[]> {
        const assets = (await this.octokit.repos.listAssetsForRelease({ ...context.repo, release_id: releaseId })).data;

        const assetNameFilter = Array.isArray(targetAssetName)
            ? (assetName: string) => targetAssetName.includes(assetName)
            : (assetName: string) => assetName === targetAssetName;

        return assets.filter(({ name }) => assetNameFilter(name));
    }

    private async deleteDuplicateAssets(releaseId: number, assetName: string | string[]): Promise<void> {
        const duplicateAssets = await this.getDuplicateAssets(releaseId, assetName);
        if (duplicateAssets.length) {
            if (this.overwrite) {
                await Promise.all(duplicateAssets.map(({ id, name }) => {
                    core.debug(`An asset called ${name} already exists in release ${this.tagName} so we'll overwrite it.`);
                    return this.octokit.repos.deleteReleaseAsset({ ...context.repo, asset_id: id });
                }));
            } else {
                core.setFailed(`Assets called ${duplicateAssets.map(({ name }) => name).join(", ")} already exists.`);
                return;
            }
        } else {
            core.debug(`No pre-existing asset called ${Array.isArray(assetName) ? assetName.join(", ") : assetName} found in release ${this.tagName}.`);
        }
    }

    private static getMIMEType(fileBytes: Buffer): string {
        const magicNum = Object.keys(MediaTypes).find((magicNum) => magicNum === fileBytes.toString("hex", 0, magicNum.length / 2)) ?? "";
        return new Map(Object.entries(MediaTypes)).get(magicNum) ?? "application/octet-stream";
    }

    private async uploadReleaseAsset(uploadUrl: string, assetPath: string, assetName = path.basename(assetPath), assetContentType?: string): Promise<UploadReleaseAssetResponse> {
        const file = await fs.readFile(assetPath);

        core.debug(`Uploading ${file} to ${assetName} in release ${this.tagName}.`);
        return this.octokit.repos.uploadReleaseAsset({
            url: uploadUrl,
            name: assetName,
            data: file,
            headers: {
                "content-type": assetContentType ?? UpdateGitHubRelease.getMIMEType(file),
                "content-length": file.byteLength
            }
        });
    }

    async uploadToReleases({ id, upload_url }: Octokit.ReposCreateReleaseResponse, files: string[]): Promise<void> {
        const assetPaths: string[] = [];
        const assetNames: string[] = [];

        for (const file of files) {
            if ((await fs.stat(file)).isFile()) {
                assetPaths.push(file);
                assetNames.push(path.basename(file));
            } else {
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

    async uploadToRelease({ id, upload_url }: Octokit.ReposCreateReleaseResponse, file: string, assetName: string, assetContentType?: string): Promise<void> {
        if (!(await fs.stat(file)).isFile()) {
            core.debug(`Skipping ${file}, since its not a file`);
            return;
        }

        await this.deleteDuplicateAssets(id, assetName);

        await this.uploadReleaseAsset(upload_url, file, assetName, assetContentType);
    }
}

async function run(): Promise<void> {
    try {
        const file = core.getInput("file", { required: true });
        const githubRelease = new UpdateGitHubRelease();
        const { octokit, tagName } = githubRelease;

        try {
            core.debug(`Get Release by tag ${tagName}.`);
            const { data: { id } } = await octokit.repos.getReleaseByTag({ ...context.repo, tag: tagName });

            await octokit.repos.deleteRelease({ ...context.repo, release_id: id });
        } catch (err) { /* continue regardless of error */ }

        const releaseInfo = (await octokit.repos.createRelease({
            ...context.repo,
            tag_name: tagName,
            name: `${tagName}: ${context.payload.head_commit.message}`,
            target_commitish: context.sha,
            draft: core.getInput("draft") === "true",
            prerelease: core.getInput("prerelease") === "true"
        })).data;

        const files = glob.sync(file);
        if (files.length) {
            if (files.length > 1) {
                await githubRelease.uploadToReleases(releaseInfo, files);
            } else {
                const assetName = core.getInput("asset_name", { required: true });
                const assetContentType = core.getInput("asset_content_type");
                await githubRelease.uploadToRelease(releaseInfo, files[0], assetName, assetContentType);
            }
        } else {
            core.setFailed("No files matching the glob pattern were found.");
        }
    } catch (error) {
        core.setFailed(error.message);
    }
}

run();
