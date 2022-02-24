const parseYaml = require("js-yaml").safeLoad;
const path = require("path");
const fs = require("fs");

function findMarkDownSync(startPath) {
  const result = [];
  const files = fs.readdirSync(path.join(__dirname, startPath));
  files.forEach(val => {
    const fPath = path.join(startPath, val);
    const stats = fs.statSync(fPath);
    if (stats.isDirectory() || path.extname(fPath).endsWith(".md")) {
      const title = stats.isDirectory() ? val : path.basename(val, ".md");
      result.push({
        title: title,
        path: fPath,
      });
    }
  });
  return result;
}

function loadYaml(fsPath) {
  return parseYaml(fs.readFileSync(path.join(__dirname, fsPath), "utf8"));
}

function loadMD(fsPath) {
  return fs.readFileSync(path.join(__dirname, fsPath), "utf8");
}

function loadBuildToolMD(fsPath) {
  const content = fs.readFileSync(path.join(__dirname, fsPath), "utf8").split("\n");
  var idx1 = content.indexOf("<!-- start -->");
  var idx2 = content.indexOf("<!-- end -->");
  if (idx1 == -1 || idx2 == -1 || idx1 >= idx2) {
    return content.join("\n");
  } else {
    return content.slice(idx1 + 1, idx2).join("\n");
  }
}

const tools = loadYaml("./tools.yml");
const buildTools = loadYaml("./build-tools.yml");

const toolsMD = findMarkDownSync("../out/tools/");
toolsMD.forEach(tool => {
  tool.install = loadMD(`${tool.path}/install.md`);
  tool.usage = loadMD(`${tool.path}/usage.md`);
});

const buildToolsMD = findMarkDownSync("../out/build-tools/");
buildToolsMD.forEach(buildTool => {
  buildTool.guide = loadBuildToolMD(buildTool.path);
});

const releaseTableMD = loadMD("../out/release-table.md")
const firstStepMD = loadMD("../out/guide/first-step.md");
const thirdStepMD = loadMD("../out/guide/third-step.md");
const lastStepMD = loadMD("../out/guide/last-step.md");

// List of projects/orgs using your project for the users page.
const users = [
  {
    caption: 'User1',
    // You will need to prepend the image path with your baseUrl
    // if it is not '/', like: '/test-site/img/docusaurus.svg'.
    image: '/img/docusaurus.svg',
    infoLink: 'https://www.facebook.com',
    pinned: true,
  },
];

const baseUrl = '/bloop/'
const repoUrl = 'https://github.com/scalacenter/bloop';
const siteConfig = {
  title: 'Bloop', // Title for your website.
  tagline: 'Compile, test and run Scala code fast',
  repoUrl,

  url: 'https://scalacenter.github.io', // Your website URL
  baseUrl,

  // For github.io type URLs, you would set the url and baseUrl like:
  //   url: 'https://facebook.github.io',
  //   baseUrl: '/test-site/',

  // Used for publishing and more
  projectName: 'bloop',
  organizationName: 'scalacenter',

  // For no header links in the top nav bar -> headerLinks: [],
  headerLinks: [
    { page: 'setup', label: 'Install' },
    { doc: 'what-is-bloop', label: 'Docs' },
    { search: true },
    { href: repoUrl, label: 'GitHub' },
  ],

  tools,
  toolsMD,
  buildTools,
  buildToolsMD,
  releaseTableMD,
  firstStepMD,
  thirdStepMD,
  lastStepMD,

  // If you have users set above, you add it here:
  users,

  /* path to images for header/footer */
  headerIcon: 'img/impure-logo-bloop.svg',
  footerIcon: 'img/impure-logo-bloop.svg',
  scalaIcon: 'img/frontpage/scala.png',
  favicon: 'img/favicon/favicon.ico',

  /* Colors for website */
  colors: {
    // primaryColor: 'rgb(39, 55, 71)',
    // primaryColor: 'rgb(27, 45, 63)',
    primaryColor: 'rgb(17, 42, 68)',

    // secondaryColor: '#05A5D1',
    secondaryColor: '#1EC2EF',

    // heroBackgroundColor: 'rgb(67, 93, 119)',
    // heroBackgroundColor: 'rgb(73, 91, 110)',
    // heroBackgroundColor: 'rgb(39, 59, 80)',
    heroBackgroundColor: 'rgba(14, 40, 68, 0.94)',

    tintColor: '#005068',
    backgroundColor: '#f5fcff',
  },

  /* Custom fonts for website */
  /*
  fonts: {
    myFont: [
      "Times New Roman",
      "Serif"
    ],
    myOtherFont: [
      "-apple-system",
      "system-ui"
    ]
  },
  */

  // This copyright info is used in /core/Footer.js and blog RSS/Atom feeds.
  credits1: "Credits to @freepik for svg icons",
  credits2: "Credits to Bazel, Babel and Metals for inspiring Bloop's website design",
  copyright: `Copyright © ${new Date().getFullYear()} Bloop`,

  highlight: {
    // Highlight.js theme to use for syntax highlighting in code blocks.
    theme: 'mono-blue',
  },

  // Necessary for sbt-docusaurus
  customDocsPath: 'out',

  // Add custom scripts here that would be placed in <script> tags.
  scripts: [
    'https://code.jquery.com/jquery-3.2.1.slim.min.js',
    'https://buttons.github.io/buttons.js',
    'https://cdnjs.cloudflare.com/ajax/libs/clipboard.js/2.0.0/clipboard.min.js',
    'https://buttons.github.io/buttons.js',
    baseUrl + 'scripts/code-block-buttons.js'
  ],

  stylesheets: [
    baseUrl + "css/code-block-buttons.css"
  ],

  // On page navigation for the current documentation page.
  onPageNav: 'separate',
  // No .html extensions for paths.
  cleanUrl: true,

  // Open Graph and Twitter card images.
  ogImage: 'img/docusaurus.png',
  twitterImage: 'img/docusaurus.png',

  // Show documentation's last contributor's name.
  // enableUpdateBy: true,

  // Show documentation's last update time.
  enableUpdateTime: true,

  // You may provide arbitrary config keys to be used as needed by your
  // template. For example, if you need your repo's URL...
  //   repoUrl: 'https://github.com/facebook/test-site',

  editUrl: `${repoUrl}/edit/main/docs/`,
  algolia: {
    apiKey: 'cf5bcb37b134346182da2be3f5e0a76b',
    indexName: 'bloop_scala'
  },
};

module.exports = siteConfig;
