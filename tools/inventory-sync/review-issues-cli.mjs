import { readFile,writeFile } from 'node:fs/promises';
import { buildIssueReview } from './issue-review.mjs';
if(process.argv.length!==4)throw Error('Usage: node review-issues-cli.mjs source.json review.json');
const source=JSON.parse(await readFile(process.argv[2],'utf8'));
await writeFile(process.argv[3],JSON.stringify(buildIssueReview(source),null,2));
