// Arrangement: the administrator (standing Configure) posts each fresh job's content.
// The Batch Control property stays as the lock left it, unless a scenario needs a switch off.
import { api, log } from './lib.mjs';

const bc = ({ ar = true, bt = true, bu = true, allowed = '' } = {}) => `
    <io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty plugin="batch-control">
      <approvalRequired>${ar}</approvalRequired><blockTimer>${bt}</blockTimer><blockUpstream>${bu}</blockUpstream>
      <allowedUpstreamJobs>${allowed}</allowedUpstreamJobs><jobApprovers/>
    </io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty>`;
const params = (defs) => `<hudson.model.ParametersDefinitionProperty><parameterDefinitions>${defs}</parameterDefinitions></hudson.model.ParametersDefinitionProperty>`;
const str = (n, d) => `<hudson.model.StringParameterDefinition><name>${n}</name><defaultValue>${d}</defaultValue><trim>false</trim></hudson.model.StringParameterDefinition>`;
const choice = (n, cs) => `<hudson.model.ChoiceParameterDefinition><name>${n}</name><choices class="java.util.Arrays$ArrayList"><a class="string-array">${cs.map((c) => `<string>${c}</string>`).join('')}</a></choices></hudson.model.ChoiceParameterDefinition>`;
const pwd = (n) => `<hudson.model.PasswordParameterDefinition><name>${n}</name><defaultValue></defaultValue></hudson.model.PasswordParameterDefinition>`;
const fs = ({ props, shell, triggers = '', publishers = '', token = '' }) => `<?xml version='1.1' encoding='UTF-8'?>
<project><description>fresh e2e-04 job</description><keepDependencies>false</keepDependencies>
  <properties>${props}</properties><scm class="hudson.scm.NullSCM"/><canRoam>true</canRoam><disabled>false</disabled>
  ${token ? `<authToken>${token}</authToken>` : ''}
  <triggers>${triggers}</triggers><concurrentBuild>false</concurrentBuild>
  <builders><hudson.tasks.Shell><command>${shell}</command></hudson.tasks.Shell></builders>
  <publishers>${publishers}</publishers><buildWrappers/></project>`;

const defs = {
  'fresh-daily': fs({ props: bc() + params(str('DATE', '2026-09-30') + choice('MODE', ['FULL', 'DELTA'])), shell: 'echo "DATE=$DATE MODE=$MODE"; sleep 3' }),
  'fresh-fail': fs({ props: bc() + params(str('P', 'x')), shell: 'echo "fail run P=$P"; true',
    publishers: '<com.chikli.hudson.plugin.naginator.NaginatorPublisher><regexpForRerun></regexpForRerun><rerunIfUnstable>false</rerunIfUnstable><rerunMatrixPart>false</rerunMatrixPart><checkRegexp>false</checkRegexp><regexpForMatrixStrategy>TestParent</regexpForMatrixStrategy><delay class="com.chikli.hudson.plugin.naginator.FixedDelay"><delay>0</delay></delay><maxSchedule>1</maxSchedule></com.chikli.hudson.plugin.naginator.NaginatorPublisher>' }),
  'fresh-cron': fs({ props: bc({ bt: false }), shell: 'echo cron tick', triggers: '<hudson.triggers.TimerTrigger><spec>* * * * *</spec></hudson.triggers.TimerTrigger>' }),
  'fresh-up-src': fs({ props: bc({ ar: false }), shell: 'echo upstream', publishers: '<hudson.tasks.BuildTrigger><childProjects>fresh-up-dst</childProjects><threshold><name>SUCCESS</name><ordinal>0</ordinal><color>BLUE</color><completeBuild>true</completeBuild></threshold></hudson.tasks.BuildTrigger>' }),
  'fresh-up-dst': fs({ props: bc({ bu: false }), shell: 'echo downstream' }),
  'fresh-token': fs({ props: bc(), shell: 'echo token', token: 'fresh-tok' }),
  'fresh-del': fs({ props: bc(), shell: 'echo del' }),
  'fresh-secret': fs({ props: bc() + params(pwd('PW') + str('Q', 'q')), shell: 'echo "Q=$Q"' }),
};
const pipe = `<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job"><description>fresh e2e-04 pipeline</description><keepDependencies>false</keepDependencies>
<properties>${bc()}${params(str('X', 'one'))}</properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>echo "hello ${'$'}{params.X}"</script><sandbox>true</sandbox></definition>
<triggers/><disabled>false</disabled></flow-definition>`;

const only = process.argv.slice(2);
for (const [name, xml] of Object.entries({ ...defs, 'fresh-pipe': pipe })) {
  if (only.length && !only.includes(name)) continue;
  const exists = (await api('admin', `/job/${name}/api/json`)).status === 200;
  const r = exists
    ? await api('admin', `/job/${name}/config.xml`, { method: 'POST', body: xml, headers: { 'Content-Type': 'application/xml' } })
    : await api('admin', `/createItem?name=${name}`, { method: 'POST', body: xml, headers: { 'Content-Type': 'application/xml' } });
  log('config', name, exists ? 'update' : 'create', r.status, r.body.slice(0, 200));
}
