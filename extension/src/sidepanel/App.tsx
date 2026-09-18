import { useState } from "react";
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

  // 스플래시 화면은 공통 헤더/푸터 껍데기를 거치지 않고 단독으로 전체 화면을 차지합니다.
  if (status === "SPLASH") {
    return (
      <div className="toss-root" data-view="splash">
        <SplashView onFinish={() => setStatus("IDLE")} />
      </div>
    );
  }

  return (
    <div className="toss-root">
      <SidepanelHeader
        showHistory={adCheck.showHistory}
        onHome={adCheck.goHome}
        onOpenHistory={adCheck.openHistory}
      />

      <main className={`toss-viewport ${status === "DETAIL_LIST" ? "toss-viewport-top" : ""}`}>
        <div className="tab-panel">
          {status === "IDLE" && <IdleView onAnalyze={adCheck.analyze} />}

          {status === "ANALYZING" && <AnalyzingView />}

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
              isFavorite={adCheck.isCurrentFavorite}
              onFilterChange={adCheck.setActiveFilter}
              onToggleFinding={adCheck.toggleFinding}
              onScrollComplete={adCheck.completeScroll}
              onLocateFinding={adCheck.locateFinding}
              onReset={adCheck.goHome}
              onToggleFavorite={() => adCheck.currentHistoryId && adCheck.toggleFavorite(adCheck.currentHistoryId)}
            />
          )}

          {(status === "ERROR" || status === "UNSUPPORTED") && (
            <IdleView variant={status} onAnalyze={adCheck.analyze} />
          )}
        </div>
      </main>

      {adCheck.isHistoryOpen && (
        <HistoryModal
          histories={adCheck.scanHistories}
          onClose={adCheck.closeHistory}
          onSelect={adCheck.selectHistory}
          onToggleFavorite={adCheck.toggleFavorite}
        />
      )}

      {/* 고지 문구는 결과 화면("위험 감지 문구가 발견되었어요")에서만 노출하고 다른 화면에서는 숨김.
          테스트 스위처는 화면과 무관하게 항상 유지 */}
      <SidepanelFooter
        testTarget={adCheck.testTarget}
        onTestTargetChange={adCheck.changeTestTarget}
        hideDisclaimer={!(status === "SUMMARY_HERO" || status === "EMPTY")}
      />
    </div>
  );
}
