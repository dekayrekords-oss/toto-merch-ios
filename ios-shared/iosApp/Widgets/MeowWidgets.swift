import SwiftUI
import WidgetKit

// A free Apple ID has no App Groups, so the widgets share no data with the app: they draw
// Lumen and the clock, and every tap opens a meowmba:// link.

struct Tick: TimelineEntry { let date: Date }

struct MinuteClock: TimelineProvider {
    func placeholder(in context: Context) -> Tick { Tick(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (Tick) -> Void) { completion(Tick(date: .now)) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<Tick>) -> Void) {
        completion(Timeline(entries: [Tick(date: .now)], policy: .after(.now.addingTimeInterval(3600))))
    }
}

private let ink = Color(red: 0.07, green: 0.09, blue: 0.16)
private let glow = Color(red: 0.55, green: 0.85, blue: 1.0)

/** Lumen's two soft eyes. */
struct LumenEyes: View {
    var body: some View {
        GeometryReader { g in
            let s = min(g.size.width, g.size.height)
            ZStack {
                Circle().fill(RadialGradient(colors: [glow.opacity(0.35), .clear], center: .center, startRadius: 0, endRadius: s / 2))
                HStack(spacing: s * 0.16) {
                    ForEach(0..<2, id: \.self) { _ in
                        Capsule().fill(glow).frame(width: s * 0.13, height: s * 0.24).shadow(color: glow, radius: s * 0.06)
                    }
                }
            }.frame(width: g.size.width, height: g.size.height)
        }
    }
}

private func chip(_ title: String, _ icon: String, _ route: String) -> some View {
    Link(destination: URL(string: "meowmba://" + route)!) {
        Label(title, systemImage: icon).font(.system(size: 13, weight: .semibold)).labelStyle(.titleAndIcon)
            .padding(.vertical, 7).frame(maxWidth: .infinity)
            .background(Color.white.opacity(0.12), in: Capsule())
    }
}

struct HomeView: View {
    @Environment(\.widgetFamily) var family
    let entry: Tick
    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack { AccessoryWidgetBackground(); Image(systemName: "pawprint.fill").font(.title2) }
                .widgetURL(URL(string: "meowmba://talk"))
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text("Meow").font(.headline)
                Text("Tap to talk").font(.caption)
            }.widgetURL(URL(string: "meowmba://talk"))
        case .systemSmall:
            VStack(spacing: 6) {
                LumenEyes().frame(height: 70)
                Text(entry.date, style: .time).font(.system(size: 22, weight: .light, design: .rounded))
                Text("Tap to talk").font(.caption2).opacity(0.7)
            }.foregroundStyle(.white).widgetURL(URL(string: "meowmba://talk"))
        default:
            HStack(spacing: 14) {
                VStack(spacing: 4) {
                    LumenEyes().frame(width: 90, height: 80)
                    Text(entry.date, style: .time).font(.system(size: 20, weight: .light, design: .rounded))
                }
                VStack(spacing: 8) {
                    chip("Talk", "mic.fill", "talk")
                    chip("Chat", "bubble.left.fill", "chat")
                    HStack(spacing: 8) { chip("Flow", "water.waves", "flow"); chip("Desk", "desktopcomputer", "desk") }
                }
            }.foregroundStyle(.white)
        }
    }
}

extension View {
    @ViewBuilder func meowBackground() -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(for: .widget) { ink }
        } else {
            padding().background(ink)
        }
    }
}

struct MeowHomeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "MeowHome", provider: MinuteClock()) { HomeView(entry: $0).meowBackground() }
            .configurationDisplayName("Meow")
            .description("Lumen and the clock. Tap to talk, chat, or open Flow or Desk.")
            .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular])
    }
}

@main
struct MeowWidgets: WidgetBundle {
    var body: some Widget { MeowHomeWidget() }
}
