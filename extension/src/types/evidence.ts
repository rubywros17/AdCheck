export interface PageTextEvidence {
  content: string;
  selector: string | null;
}

export interface PageImageEvidence {
  url: string;
  alt: string | null;
}

export interface PageEvidence {
  pageUrl: string;
  pageTitle: string | null;
  productName: string | null;
  texts: PageTextEvidence[];
  images: PageImageEvidence[];
}
