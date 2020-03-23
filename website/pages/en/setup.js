const React = require("react");

const CompLibrary = require("../../core/CompLibrary.js");
const translate = require("../../server/translate.js").translate;
const Container = CompLibrary.Container;
const MarkdownBlock = CompLibrary.MarkdownBlock;

const Prism = require("prismjs")
const PrismJava = require("prismjs/components/prism-java");
const PrismScala = require("prismjs/components/prism-scala");

// Import paths to markdown files of the setup
const siteConfig = require(process.cwd() + "/siteConfig.js");
const toolsMD = siteConfig.toolsMD;
const buildToolsMD = siteConfig.buildToolsMD;
const releaseTableMD = siteConfig.releaseTableMD;

const SetupHeader = () => {
  return (
    <div className="page-header text-center installation-header">
      <h1>
        <translate desc="setup page - header">Installation Guide</translate>
      </h1>
    </div>
  );
};

const SetupSelectButton = props => {
  const showTools = Object.keys(props.types.items).map((tool, j) => {
    return (
      <button
        key={j}
        data-title={tool}
        className={props.buttonClassName}
      >
        {props.types.items[tool]}
      </button>
    );
  });
  return (
    <div className={props.divClassName}>
      <h5>{props.types.name}</h5>
      {showTools}
    </div>
  );
};

const SetupInstallationHeader = () => {
  return (
    <div className="step-setup">
      <h2 id="installation">
        <span className="step-no">1</span>
        <translate desc="setup page - step 1">
          Do you need to install Bloop?
        </translate>
      </h2>
      <MarkdownBlock>
        {siteConfig.firstStepMD}
      </MarkdownBlock>
    </div>
  )
};

const SetupOptions = () => {
  const tools = siteConfig.tools;
  const showCase = tools.map((types, i) => {
    return (
      <SetupSelectButton
        buttonClassName={"tools-button"}
        divClassName={"tools-group"}
        key={i}
        types={types}
      />
    );
  });
  const markdownsElement = toolsMD.map((tool, index) => (
    <div className="items" data-title={tool.title} key={index}>
      <MarkdownBlock key={index}>{tool["install"]}</MarkdownBlock>
    </div>
  ));
  return (
    <div className="step-setup">
      <h2 id="installation">
        <span className="step-no">2</span>
        <translate desc="setup page - step 2">
          Pick your installation method
        </translate>
      </h2>
      <div className="tools-group-all">
        {showCase}
      </div>
      <div className="step-hidden step-setup">
        {markdownsElement}
      </div>
    </div>
  );
};

const ExportBuild = () => {
  const buildTools = siteConfig.buildTools;
  const showCase = buildTools.map((types, i) => {
    return (
      <SetupSelectButton
        buttonClassName={"tools-button"}
        divClassName={"tools-group"}
        key={i}
        types={types}
      />
    );
  });
  const markdownsElement = buildToolsMD.map((tool, index) => (
    <div className="items" data-title={tool.title} key={index}>
      <MarkdownBlock key={index}>{tool["guide"]}</MarkdownBlock>
    </div>
  ));
  return (
    <div className="step-setup">
      <h2 id="installation">
        <span className="step-no">3</span>
        <translate desc="setup page - step 3">
          Set up your build
        </translate>
      </h2>
      <MarkdownBlock>
        {siteConfig.thirdStepMD}
      </MarkdownBlock>
      <div className="tools-group-all">
        {showCase}
      </div>
      <div className="step-hidden step-setup">
        {markdownsElement}
      </div>
    </div>
  );
};

const UseBloop = () => {
  const usage = <translate desc="setup page - step 4">Use Bloop</translate>;
  return (
    <div className="step-setup">
      <h2>
        <span className="step-no">4</span>
        {usage}
      </h2>
      <MarkdownBlock>
        {siteConfig.lastStepMD}
      </MarkdownBlock>
    </div>
  );
};

const SetupContent = () => {
  return (
    <Container padding={["bottom"]}>
      <div className="step">
        <SetupInstallationHeader />
        <SetupOptions />
        <ExportBuild />
        <UseBloop />
      </div>
    </Container>
  );
};

class Setup extends React.Component {
  constructor(props) {
    super(props);
  }
  render() {
    const time = new Date().getTime();
    return (
      <div className="mainContainer">
        <div className="installationContainer">
          <SetupHeader />
          <SetupContent />
          <script src={`${siteConfig.baseUrl}scripts/tools.js?t=${time}`} />
          <script src={`${siteConfig.baseUrl}scripts/build-tools.js?t=${time}`} />
        </div>
      </div>
    );
  }
}

module.exports = Setup;
