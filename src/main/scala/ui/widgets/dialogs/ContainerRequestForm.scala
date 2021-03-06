package ui.widgets.dialogs

import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactEventI}
import model._
import org.scalajs.dom
import org.scalajs.dom.ext.AjaxException
import ui.WorkbenchRef
import ui.widgets.Alert
import util.StringUtils._
import util.logger._

import scala.concurrent.ExecutionContext.Implicits.global

object ContainerRequestForm {

  trait ActionsBackend {
    def newContainerCreated(containerId: String)
  }

  case class State(request: CreateContainerRequest,
                   portsRadioOption: PortsRadioOptions = AllPorts,
                   warnings: Seq[String] = Seq.empty,
                   cmdText: String = "",
                   message: Option[String] = None) {

  }

  case class Props(actionsBackend: ActionsBackend, image: Image, initialConfig: ContainerConfig, ref: WorkbenchRef) {
    def imageName = image.RepoTags.headOption.getOrElse(image.Id)
  }

  case class Backend(t: BackendScope[Props, State]) {

    def didMount(): Unit = {
      // The Dialog is not a react component
      dom.document.getElementById("open-modal-dialog").asInstanceOf[dom.raw.HTMLButtonElement].click()
      t.modState(s => s.copy(cmdText = s.request.cmd.mkString(" ")))
    }


    def updateName(e: ReactEventI) =
      t.modState(s => s.copy(request = s.request.copy(name = e.target.value.replaceAll("\\s", "")), warnings = Seq.empty))


    def updateCmd(e: ReactEventI) = {
      val cmd = e.target.value.split("\\s+").toSeq
      t.modState(s => s.copy(cmdText = e.target.value, request = s.request.copy(Cmd = cmd)))
    }

    def onStCheckBox(e: ReactEventI) =
      t.modState(s => s.copy(request = s.request.copy(OpenStdin = !s.request.OpenStdin, Tty = !s.request.Tty)))

    def stCheckBoxValue = t.state.request.OpenStdin && t.state.request.Tty

    def portsMapping(option: PortsRadioOptions): Unit = {
      val hostConfig = t.state.request.HostConfig
      val request = option match {
        case AllPorts => t.state.request.copy(HostConfig = hostConfig.copy(PublishAllPorts = true, PortBindings = Map.empty))
        case NoPorts => t.state.request.copy(HostConfig = hostConfig.copy(PublishAllPorts = false, PortBindings = Map.empty))
        case CustomPorts => t.state.request.copy(
          HostConfig = hostConfig.copy(
            PublishAllPorts = false,
            PortBindings = t.props.initialConfig.portBindings.map { case (containerPort, host) =>
              (containerPort, if (host.isEmpty) Seq(NetworkSettingsPort(HostIp = "", HostPort = "")) else host)
            }
          )
        )
      }

      t.modState(s => s.copy(portsRadioOption = option, request = request))
    }

    def updateBinding(containerPort: String, binding: NetworkSettingsPort)(e: ReactEventI): Unit = {
      val oldBinding = t.state.request.HostConfig.PortBindings
      val bindings = oldBinding(containerPort)
      val updatedBindings = oldBinding.updated(containerPort,
        bindings.updated(bindings.indexOf(binding), binding.copy(HostPort = e.target.value)))
      t.modState(_.copy(request = t.state.request.copy(
        HostConfig = t.state.request.HostConfig.copy(PortBindings = updatedBindings)
      )))
    }

    def run(): Unit = t.props.ref.client.map { client =>
      val task = for {
        response <- client.createContainer(t.state.request.name, t.state.request)
        _ <- client.startContainer(response.Id)
      } yield {
          if (response.Id.isEmpty) {
            t.modState(s => s.copy(warnings = response.Warnings))
          } else {
            dom.document.getElementById("open-modal-dialog").asInstanceOf[dom.raw.HTMLButtonElement].click()
            dom.setTimeout(() => t.props.actionsBackend.newContainerCreated(response.Id), 1) // delay after animation
          }
        }

      task.onFailure {
        case ex: AjaxException =>
          log.error("ImagesPage", "Unable to get Metadata", ex)
          t.modState(s => s.copy(warnings = Seq(ex.xhr.responseText)))
      }
    }

    def textCommand = {
      val request = t.state.request
      val cmd = request.cmd.mkString(" ")
      val imageName = t.props.imageName
      val nameCommand = if (request.name.isEmpty) "" else s" --name ${request.name}"
      val paramI = if (request.OpenStdin) " -i" else ""
      val paramT = if (request.Tty) " -t" else ""
      def ports = t.state.portsRadioOption match {
        case AllPorts => " -P"
        case NoPorts => ""
        case CustomPorts => request.HostConfig.PortBindings
          .flatMap { case (containerPort, hostPorts) =>
          hostPorts.map(h => (substringBefore(containerPort, "/"), h.HostPort))
        }.filter(_._2.nonEmpty).map { case (internal, external) => s"-p $external:$internal" }
          .mkString(" ", " ", "")
      }
      s"docker run$paramI$paramT$ports$nameCommand $imageName $cmd"
    }
  }

  def apply(actionsBackend: ActionsBackend, image: Image, initialConfig: ContainerConfig, ref: WorkbenchRef) = {
    val props = Props(actionsBackend, image, initialConfig, ref)
    val exports = initialConfig.ExposedPorts
    val request = CreateContainerRequest(
      AttachStdin = true,
      AttachStdout = true,
      AttachStderr = true,
      Tty = true,
      OpenStdin = true, // opens stdin
      Cmd = initialConfig.Cmd,
      Image = props.imageName,
      HostConfig = HostConfig(PublishAllPorts = true, PortBindings = Map.empty),
      ExposedPorts = exports,
      name = "")
    val initialState = State(request)
    ContainerRequestFormRender.component(initialState)(props)
  }

}

object ContainerRequestFormRender {

  import ui.widgets.dialogs.ContainerRequestForm._

  def component(initialState: State) = ReactComponentB[Props]("ContainerConfigForm")
    .initialState(initialState)
    .backend(new Backend(_))
    .render((P, S, B) => vdom(P, S, B))
    .componentDidMount(_.backend.didMount)
    .build

  val data_toggle = "data-toggle".reactAttr
  val data_target = "data-target".reactAttr
  var data_dismiss = "data-dismiss".reactAttr
  var data_trigger = "data-trigger".reactAttr


  def vdom(P: Props, S: State, B: Backend) = {

    <.div(^.className := "modal fade", ^.id := "editModal", ^.role := "dialog",
      <.div(^.className := "modal-dialog",
        <.div(^.className := "modal-content",
          <.div(^.className := "modal-header",
            <.div(^.className := "btn-group pull-left",
              <.button(^.className := "btn btn-danger", data_dismiss := "modal", "Cancel")
            ),
            <.div(^.className := "btn-group pull-right",
              <.button(^.id := "open-modal-dialog", ^.display := "none",
                data_toggle := "modal", data_target := "#editModal", "Open"),
              <.button(^.className := "btn btn-success", ^.onClick --> B.run, <.span(^.className := "glyphicon glyphicon-play"), "Run")
            ),
            <.div(^.className := "modal-title",
              <.span("Create new Container"), <.br(),
              <.i("Using the image: "), <.strong(P.imageName)
            )
          ),
          <.div(^.className := "modal-body",
            S.message.map(<.i(_)),
            S.warnings.map(Alert(_)),
            <.form(^.className := "form-horizontal",
              <.div(^.className := "form-group",
                <.label(^.className := "col-xs-3 control-label", "Container Name"),
                <.div(^.className := "col-xs-9",
                  <.input(^.`type` := "text", ^.className := "form-control", ^.placeholder := "Container name (optional) ",
                    ^.value := S.request.name, ^.onChange ==> B.updateName)
                )
              ),
              <.div(^.className := "form-group",
                <.label(^.className := "col-xs-3 control-label", "Command"),
                <.div(^.className := "col-xs-9",
                  <.input(^.`type` := "text", ^.className := "form-control", ^.placeholder := "Command",
                    ^.value := S.cmdText, ^.onChange ==> B.updateCmd)
                )
              ),
              <.div(^.className := "form-group",
                <.label(^.className := "col-xs-3 control-label", "Ports"),
                <.div(^.className := "col-xs-9 btn-group", data_toggle := "buttons",
                  <.label(^.onClick --> B.portsMapping(AllPorts), ^.className := "btn btn-primary  active", <.input(^.`type` := "radio", ^.className := "publicPorts", ^.name := "ports"), "All"),
                  <.label(^.onClick --> B.portsMapping(CustomPorts), ^.className := "btn btn-primary", <.input(^.`type` := "radio", ^.className := "publicPorts", ^.name := "ports"), "Custom"),
                  <.label(^.onClick --> B.portsMapping(NoPorts), ^.className := "btn btn-primary", <.input(^.`type` := "radio", ^.className := "publicPorts", ^.name := "ports"), "None")
                )
              ),
              S.portsRadioOption == CustomPorts ?= table(S, B),
              <.div(^.className := "form-group",
                <.label(^.className := "col-xs-3 control-label", "stdin/stout"),
                <.div(^.className := "col-xs-9",
                  <.input(^.`type` := "checkbox", ^.checked := B.stCheckBoxValue, ^.onClick ==> B.onStCheckBox,
                    <.span(" Keep STDIN open & Allocate a pseudo-TTY", <.br(), <.small("you can connect later in interactive mode."))
                  )
                )
              )
            )
          ),
          <.div(^.className := "modal-footer",
            <.i(^.className := "glyphicon glyphicon-console pull-left", <.code(B.textCommand))
          )
        )
      )
    )
  }

  def table(S: State, B: Backend) =
    <.div(^.className := "form-group",
      <.div(^.className := "container  col-sm-12",
        <.table(^.className := "table table-hover table-striped",
          <.thead(
            <.tr(
              <.th("Host Ports"),
              <.th(<.i(^.className := "fa fa-plug")),
              <.th("Container Port")
            )
          ),
          <.tbody(
            S.request.HostConfig.PortBindings.map { case (containerPort, hostPorts) =>
              hostPorts.map { host =>
                <.tr(
                  <.td(<.input(^.`type` := "text", ^.className := "form-control", ^.placeholder := "Exposed port in the host",
                    ^.value := host.HostPort, ^.onChange ==> B.updateBinding(containerPort, host))
                  ),
                  <.th(<.i(^.className := "fa fa-arrow-right")),
                  <.td(containerPort)
                )
              }
            }
          )
        )
      )

    )


}

sealed trait PortsRadioOptions

case object AllPorts extends PortsRadioOptions

case object CustomPorts extends PortsRadioOptions

case object NoPorts extends PortsRadioOptions