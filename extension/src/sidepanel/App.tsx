import { useState } from "react";
import { SCAN_CYCLE_MS } from "./data";
import { useAdCheck } from "./hooks/useAdCheck";
import type { ViewStatus } from "./types";
import { SidepanelHeader } from "./components/SidepanelHeader";
import { SidepanelFooter } from "./components/SidepanelFooter";
import { HistoryModal } from "./components/HistoryModal";
import { SplashView } from "./views/SplashView";
import { IdleView } from "./views/IdleView";
import { AnalyzingView } from "./views/AnalyzingView";
import { SummaryHeroView } from "./views/SummaryHeroView";
import { BubblePreviewView } from "./views/BubblePreviewView";
import { DetailListView } from "./views/DetailListView";

export function App() {
  const [status, setStatus] = useState<ViewStatus>("SPLASH");
  const adCheck = useAdCheck(status, setStatus);

  return (
    <div className="toss-root">
      {status === "SPLASH" && <SplashView onComplete={() => setStatus("IDLE")} />}

      <SidepanelHeader showHistory={adCheck.showHistory} onHome={adCheck.goHome} onOpenHistory={adCheck.openHistory} />

      <main className={`toss-viewport ${status === "DETAIL_LIST" ? "toss-viewport-top" : ""}`}>
        <div className="tab-panel">
          {status === "IDLE" && <IdleView onAnalyze={adCheck.analyze} onReset={adCheck.goHome} />}

          {status === "ANALYZING" && <AnalyzingView scanCycleMs={SCAN_CYCLE_MS} />}

          {(status === "SUMMARY_HERO" || status === "EMPTY") && (
            <SummaryHeroView
              count={adCheck.targetCount}
              level={adCheck.level}
              onContinue={() => (status === "EMPTY" ? adCheck.analyze() : setStatus("BUBBLE_PREVIEW"))}
            />
          )}

          {status === "BUBBLE_PREVIEW" && (
            <BubblePreviewView
              findings={adCheck.findings}
              onBack={() => setStatus("SUMMARY_HERO")}
              onSelect={adCheck.selectBubble}
              onShowAll={adCheck.showAllFindings}
            />
          )}

          {status === "DETAIL_LIST" && (
            <DetailListView
              findings={adCheck.findings}
              activeFilter={adCheck.activeFilter}
              expandedFindings={adCheck.expandedFindings}
              pendingScrollIdx={adCheck.pendingScrollIdx}
              currentPageTitle={adCheck.currentPageTitle}
              pageUrl={adCheck.pageUrl}
              onBack={() => setStatus("BUBBLE_PREVIEW")}
              onShare={adCheck.shareResults}
              onFilterChange={adCheck.setActiveFilter}
              onToggleFinding={adCheck.toggleFinding}
              onScrollComplete={adCheck.completeScroll}
              onLocateFinding={adCheck.locateFinding}
              onAnalyze={adCheck.analyze}
              onReset={adCheck.goHome}
            />
          )}

          {(status === "ERROR" || status === "UNSUPPORTED") && (
            <IdleView variant={status} onAnalyze={adCheck.analyze} onReset={adCheck.goHome} />
          )}
        </div>
      </main>

      {adCheck.isHistoryOpen && (
        <HistoryModal histories={adCheck.scanHistories} onClose={adCheck.closeHistory} onSelect={adCheck.selectHistory} />
      )}

      <SidepanelFooter testTarget={adCheck.testTarget} onTestTargetChange={adCheck.changeTestTarget} />
    </div>
  );
}
