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
    <p>
      The installation guide walks you through all the steps to to install and get any
      of the supported build tools working with Bloop.
    </p>
      
      <div style={{"display": "flex", "justifyContent": "center", "marginBottom": "1em"}}>
        <MarkdownBlock key={"release-table"}>{releaseTableMD}</MarkdownBlock>
      </div>
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
  return (
    <div className="step-setup">
      <h2>
        <span className="step-no">1</span>
        <translate desc="setup page - step 1">
          Pick your preferred method
        </translate>
      </h2>
      {showCase}
    </div>
  );
};

const StepInstallAndUsage = props => {
  const markdownsElement = toolsMD.map((tool, index) => (
    <div className="items" data-title={tool.title} key={index}>
      <MarkdownBlock key={index}>{tool[props.name]}</MarkdownBlock>
    </div>
  ));
  const installation = (
    <translate desc="setup page - step 2">Installation</translate>
  );
  const usage = <translate desc="setup page - step 3">Usage</translate>;
  return (
    <div className="step-hidden step-setup">
      <h2 id={props.name === "install" ? "installation" : ""}>
        <span className="step-no">{props.number}</span>
        {props.name === "install" ? installation : usage}
      </h2>
      {markdownsElement}
    </div>
  );
};

const StepFour = () => {
  const buildToolButtons = siteConfig.buildTools.map((types, i) => {
    return (
      <SetupSelectButton
          buttonClassName={"build-tools-button"}
          divClassName={"build-tools-group"}
          key={i}
          types={types}
      />
    );
  });

  const buildToolExportGuides = buildToolsMD.map((tool, index) => (
    <div className="build-items" data-title={tool.title} key={index}>
      <MarkdownBlock key={index}>{tool["export"]}</MarkdownBlock>
    </div>
  ));

  return (
    <div id="build-tools" className="step-hidden step-setup">
      <h2>
        <span className="step-no">4</span>
        <translate desc="setup page - step 4 one">Export your build</translate>
      </h2>
      {buildToolButtons}
      {buildToolExportGuides}
    </div>
  );
};

const SetupContent = () => {
  return (
    <Container padding={["bottom"]}>
      <div className="step">
        <SetupOptions />
        <StepInstallAndUsage name="install" number="2" />
        <StepInstallAndUsage name="usage" number="3" />
        <StepFour />
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
